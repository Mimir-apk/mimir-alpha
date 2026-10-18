package com.gruning.mimir.tweaker.mgde

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import com.gruning.mimir.tweaker.MimirTmpRootReservations
import java.io.File
import java.io.FileDescriptor

/** D7 is a sealed observation, not a portability claim. */
internal object MgdeD7Pins {
    const val COMMIT = "8b377b4ae37590980de3e73e3758c814d04dba73"
    const val MANIFEST_SHA256 = "dd13758c4455b65980deb29aa5e2eeff7828502f0c705d85e309df9e014f5700"
}

internal data class MgdeFileStat(val dev: Long, val ino: Long, val uid: Int, val gid: Int, val mode: Int, val nlink: Long)

internal sealed interface MgdeStatResult {
    data class Present(val stat: MgdeFileStat) : MgdeStatResult
    data object Absent : MgdeStatResult
    data object Unknown : MgdeStatResult
}

internal fun classifyLstatErrno(
    errno: Int,
    enoent: Int = OsConstants.ENOENT,
): MgdeStatResult =
    if (errno == enoent) {
        MgdeStatResult.Absent
    } else {
        MgdeStatResult.Unknown
    }

internal fun classifyLstatFailure(error: Throwable): MgdeStatResult =
    if (error is ErrnoException) {
        classifyLstatErrno(error.errno)
    } else {
        MgdeStatResult.Unknown
    }

internal enum class MgdeLinkNoReplaceResult { LINKED, DESTINATION_EXISTS, FAILED }
internal interface MgdePosixOps {
    fun immediateNames(directory: String): Set<String>
    fun openExclusiveNoFollow(path: String, mode: Int): FileDescriptor
    fun openReadNoFollow(path: String): FileDescriptor
    fun writeAll(fd: FileDescriptor, bytes: ByteArray): Boolean
    fun readBounded(fd: FileDescriptor, maxBytes: Int): ByteArray?
    fun fsync(fd: FileDescriptor): Boolean
    fun close(fd: FileDescriptor)
    fun lstat(path: String): MgdeStatResult
    fun fstat(fd: FileDescriptor): MgdeFileStat?
    fun securityLabel(path: String): String?
    fun linkNoReplace(existing: String, destination: String): MgdeLinkNoReplaceResult
    fun removeExact(path: String): Boolean
    fun rmdirExact(path: String): Boolean
    fun fsyncDirectory(directory: String): Boolean
}

/** Android implementation; it is intentionally unwired and has no label-source policy. */
internal object AndroidMgdePosixOps : MgdePosixOps {
    override fun immediateNames(directory: String): Set<String> = File(directory).list()?.toSet() ?: setOf("<unreadable>")
    override fun openExclusiveNoFollow(path: String, mode: Int): FileDescriptor = Os.open(path,
        OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, mode)
    override fun openReadNoFollow(path: String): FileDescriptor = Os.open(
        path,
        OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0,
    )
    override fun writeAll(fd: FileDescriptor, bytes: ByteArray): Boolean = runCatching {
        var at = 0; while (at < bytes.size) { val n = Os.write(fd, bytes, at, bytes.size - at); if (n <= 0) return false; at += n }; true
    }.getOrDefault(false)
    override fun readBounded(fd: FileDescriptor, maxBytes: Int): ByteArray? = runCatching {
        val sink = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val remaining = maxBytes + 1 - sink.size()
            if (remaining <= 0) return null
            val count = Os.read(fd, buffer, 0, minOf(buffer.size, remaining))
            if (count == 0) break
            require(count > 0)
            sink.write(buffer, 0, count)
        }
        sink.toByteArray().takeIf { it.size in 1..maxBytes }
    }.getOrNull()
    override fun fsync(fd: FileDescriptor): Boolean = runCatching { Os.fsync(fd); true }.getOrDefault(false)
    override fun close(fd: FileDescriptor) { runCatching { Os.close(fd) } }
    override fun lstat(path: String): MgdeStatResult = try {
        Os.lstat(path).let {
            MgdeStatResult.Present(MgdeFileStat(it.st_dev, it.st_ino, it.st_uid, it.st_gid, it.st_mode, it.st_nlink))
        }
    } catch (error: Throwable) {
        classifyLstatFailure(error)
    }
    override fun fstat(fd: FileDescriptor): MgdeFileStat? = runCatching {
        Os.fstat(fd).let { MgdeFileStat(it.st_dev, it.st_ino, it.st_uid, it.st_gid, it.st_mode, it.st_nlink) }
    }.getOrNull()
    override fun securityLabel(path: String): String? = runCatching { Os.getxattr(path, "security.selinux").toString(Charsets.UTF_8).trimEnd('\u0000') }.getOrNull()
    override fun linkNoReplace(existing: String, destination: String): MgdeLinkNoReplaceResult = try {
        Os.link(existing, destination)
        MgdeLinkNoReplaceResult.LINKED
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.EEXIST) {
            MgdeLinkNoReplaceResult.DESTINATION_EXISTS
        } else {
            MgdeLinkNoReplaceResult.FAILED
        }
    } catch (_: Throwable) {
        MgdeLinkNoReplaceResult.FAILED
    }
    override fun removeExact(path: String): Boolean = runCatching { Os.remove(path); true }.getOrDefault(false)
    /** libcore exposes directory removal through remove; this method remains distinct at the policy seam. */
    override fun rmdirExact(path: String): Boolean = runCatching { Os.remove(path); true }.getOrDefault(false)
    override fun fsyncDirectory(directory: String): Boolean = runCatching {
        val fd = Os.open(directory, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try { Os.fsync(fd); true } finally { Os.close(fd) }
    }.getOrDefault(false)
}

internal sealed interface MgdePublicationResult {
    data object Published : MgdePublicationResult
    data object Rejected : MgdePublicationResult
    data object Ambiguous : MgdePublicationResult
}

internal sealed interface MgdeCleanupResult {
    /** Reserved for a future cleanup implementation with an approved exclusive namespace owner. */
    data object Cleaned : MgdeCleanupResult

    /** Provably no file or directory was removed by this call. */
    data object Rejected : MgdeCleanupResult

    /** Reserved for a future cleanup implementation after its first identity-bound mutation. */
    data object Ambiguous : MgdeCleanupResult
}

/**
 * The admission boundary accepts only a fresh store observation. Callers may name absence, which
 * can never grant authority, but cannot construct a positive record/locator observation.
 */
internal sealed interface MgdeObservedSource {
    /**
     * Rejection-only observation: the MGDE root or session directory is provably absent (ENOENT).
     * Never a publication precondition; [MgdeD7PublicationStore.publish] always rejects when
     * directories are missing or unstatable.
     */
    data object Absent : MgdeObservedSource
    data object MalformedPresent : MgdeObservedSource
    fun attestedLocator(): String? = null
    fun attestedRecord(): ByteArray? = null
}

private class AttestedMgdePresent(
    private val locator: String,
    record: ByteArray,
) : MgdeObservedSource {
    private val pinnedRecord = record.copyOf()
    override fun attestedLocator(): String = locator
    override fun attestedRecord(): ByteArray = pinnedRecord.copyOf()
}

/**
 * Unwired no-replace publication proof model. A locator is a derived 16-lower-hex token; no user
 * filename is accepted. Production must not construct this store until one exclusive privileged
 * namespace owner is specified and proven: the stage-name unlink is not substitution-safe without
 * that contract. [cleanupResidue] therefore remains policy-disabled.
 */
internal class MgdeD7PublicationStore(
    private val ops: MgdePosixOps = AndroidMgdePosixOps,
) {
    companion object {
        internal const val ROOT = MimirTmpRootReservations.MGDE_PUBLICATION
        const val UID = 0; const val GID = 0; const val LABEL = "u:object_r:shell_data_file:s0"
        const val DIRECTORY_MODE = 0x1c0; const val RECORD_MODE = 0x180
        /** One fixed-root writer/observer/cleanup authority across all store instances in-process. */
        private val PROCESS_LOCK = Any()
    }
    private fun validLocator(locator: String) = locator.matches(Regex("[0-9a-f]{16}"))
    private fun directory(locator: String) = "$ROOT/$locator"
    private fun stage(locator: String) = "${directory(locator)}/.stage"
    private fun record(locator: String) = "${directory(locator)}/record"
    private fun cleanNames() = emptySet<String>()
    private fun stageNames() = setOf(".stage")
    private fun recordNames() = setOf("record")
    private fun secure(path: String, links: Long): MgdeFileStat? = when (val result = ops.lstat(path)) {
        is MgdeStatResult.Present -> secureRecord(result, links, path)
        else -> null
    }
    private fun secureRecord(present: MgdeStatResult.Present, links: Long, path: String): MgdeFileStat? =
        present.stat.takeIf { secureRecordStat(it, links) && ops.securityLabel(path) == LABEL }
    private fun secureRecordStat(stat: MgdeFileStat, links: Long): Boolean =
        (stat.mode and OsConstants.S_IFMT) == OsConstants.S_IFREG &&
            (stat.mode and 0xfff) == RECORD_MODE &&
            stat.uid == UID &&
            stat.gid == GID &&
            stat.nlink == links
    private fun secureDirectoryStat(stat: MgdeFileStat): Boolean =
        (stat.mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
            (stat.mode and 0xfff) == DIRECTORY_MODE &&
            stat.uid == UID &&
            stat.gid == GID &&
            stat.nlink >= 2
    private fun secureDirectory(path: String): MgdeFileStat? = when (val result = ops.lstat(path)) {
        is MgdeStatResult.Present -> result.stat.takeIf {
            secureDirectoryStat(it) && ops.securityLabel(path) == LABEL
        }
        else -> null
    }
    private fun attestDirectory(path: String, result: MgdeStatResult): MgdeFileStat? = when (result) {
        is MgdeStatResult.Present -> result.stat.takeIf {
            secureDirectoryStat(it) && ops.securityLabel(path) == LABEL
        }
        else -> null
    }

    /**
     * Re-reads [path] and confirms the entry still matches [pinned] identity (dev/ino) with secure
     * directory label/type/owner/mode. Narrows but does not close path-only rename-away/rename-back
     * TOCTOU; production still requires the same exclusive privileged namespace owner contract that
     * blocks [cleanupResidue].
     */
    private fun stillAttested(path: String, pinned: MgdeFileStat): Boolean {
        val result = ops.lstat(path)
        if (result !is MgdeStatResult.Present) {
            return false
        }
        val stat = result.stat
        return stat.dev == pinned.dev &&
            stat.ino == pinned.ino &&
            secureDirectoryStat(stat) &&
            ops.securityLabel(path) == LABEL
    }

    internal fun observe(taskNonce: String): MgdeObservedSource = synchronized(PROCESS_LOCK) {
        val locator = MgdeAdmission.locatorFor(taskNonce)
        val dir = directory(locator)
        if (!validLocator(locator)) {
            return MgdeObservedSource.MalformedPresent
        }
        val rootResult = ops.lstat(ROOT)
        when (rootResult) {
            MgdeStatResult.Absent -> return MgdeObservedSource.Absent
            MgdeStatResult.Unknown -> return MgdeObservedSource.MalformedPresent
            is MgdeStatResult.Present -> Unit
        }
        val pinnedRoot = attestDirectory(ROOT, rootResult)
            ?: return MgdeObservedSource.MalformedPresent
        val sessionResult = ops.lstat(dir)
        when (sessionResult) {
            MgdeStatResult.Absent -> return MgdeObservedSource.Absent
            MgdeStatResult.Unknown -> return MgdeObservedSource.MalformedPresent
            is MgdeStatResult.Present -> Unit
        }
        val pinnedSession = attestDirectory(dir, sessionResult)
            ?: return MgdeObservedSource.MalformedPresent
        val names = ops.immediateNames(dir)
        if (names.isEmpty()) {
            return if (stillAttested(ROOT, pinnedRoot) && stillAttested(dir, pinnedSession)) {
                MgdeObservedSource.Absent
            } else {
                MgdeObservedSource.MalformedPresent
            }
        }
        if (names != recordNames()) return MgdeObservedSource.MalformedPresent
        val path = record(locator)
        val before = secure(path, 1) ?: return MgdeObservedSource.MalformedPresent
        val fd = runCatching { ops.openReadNoFollow(path) }.getOrNull()
            ?: return MgdeObservedSource.MalformedPresent
        val opened = ops.fstat(fd)
            ?: run {
                ops.close(fd)
                return MgdeObservedSource.MalformedPresent
            }
        val bytes = try {
            if (
                !secureRecordStat(opened, 1) ||
                opened.dev != before.dev ||
                opened.ino != before.ino
            ) {
                null
            } else {
                ops.readBounded(fd, MgdeV2Codec.MAX_RECORD)
            }
        } finally {
            ops.close(fd)
        } ?: return MgdeObservedSource.MalformedPresent
        val after = secure(path, 1) ?: return MgdeObservedSource.MalformedPresent
        if (
            opened.dev != after.dev ||
            opened.ino != after.ino ||
            before.dev != after.dev ||
            before.ino != after.ino ||
            ops.immediateNames(dir) != recordNames()
        ) {
            return MgdeObservedSource.MalformedPresent
        }
        if (!stillAttested(ROOT, pinnedRoot) || !stillAttested(dir, pinnedSession)) {
            return MgdeObservedSource.MalformedPresent
        }
        return AttestedMgdePresent(locator, bytes)
    }

    fun publish(taskNonce: String, payload: ByteArray): MgdePublicationResult = synchronized(PROCESS_LOCK) {
        val locator = MgdeAdmission.locatorFor(taskNonce)
        if (
            !validLocator(locator) ||
            payload.size !in 1..MgdeV2Codec.MAX_RECORD ||
            secureDirectory(ROOT) == null ||
            secureDirectory(directory(locator)) == null ||
            ops.immediateNames(directory(locator)) != cleanNames()
        ) {
            return MgdePublicationResult.Rejected
        }
        val stage = stage(locator); val record = record(locator)
        val fd = runCatching { ops.openExclusiveNoFollow(stage, RECORD_MODE) }.getOrNull() ?: return MgdePublicationResult.Rejected
        var writtenStat: MgdeFileStat? = null
        val written = try {
            (ops.writeAll(fd, payload) && ops.fsync(fd)).also { complete ->
                if (complete) writtenStat = ops.fstat(fd)
            }
        } finally {
            ops.close(fd)
        }
        val pinned = writtenStat
        val stagedBeforeLink = secure(stage, 1)
        if (
            !written ||
            pinned == null ||
            !secureRecordStat(pinned, 1) ||
            stagedBeforeLink == null ||
            pinned.dev != stagedBeforeLink.dev ||
            pinned.ino != stagedBeforeLink.ino ||
            ops.immediateNames(directory(locator)) != stageNames()
        ) {
            return MgdePublicationResult.Rejected
        }
        when (ops.linkNoReplace(stage, record)) {
            MgdeLinkNoReplaceResult.LINKED -> Unit
            MgdeLinkNoReplaceResult.DESTINATION_EXISTS -> return MgdePublicationResult.Rejected
            MgdeLinkNoReplaceResult.FAILED -> return MgdePublicationResult.Ambiguous
        }
        val staged = secure(stage, 2) ?: return MgdePublicationResult.Ambiguous
        val final = secure(record, 2) ?: return MgdePublicationResult.Ambiguous
        if (
            staged.dev != pinned.dev ||
            staged.ino != pinned.ino ||
            final.dev != pinned.dev ||
            final.ino != pinned.ino ||
            !ops.removeExact(stage)
        ) {
            return MgdePublicationResult.Ambiguous
        }
        if (
            secure(record, 1) == null ||
            ops.immediateNames(directory(locator)) != recordNames() ||
            !ops.fsyncDirectory(directory(locator))
        ) {
            return MgdePublicationResult.Ambiguous
        }
        return MgdePublicationResult.Published
    }

    /**
     * Cleanup is deliberately policy-disabled. Android exposes only pathname unlink at this seam;
     * attesting and then unlinking would let another privileged writer substitute the named entry.
     * Residues stay intact until an exclusive privileged namespace owner is specified and proven.
     */
    @Suppress("UNUSED_PARAMETER")
    fun cleanupResidue(taskNonce: String): MgdeCleanupResult = MgdeCleanupResult.Rejected
}
