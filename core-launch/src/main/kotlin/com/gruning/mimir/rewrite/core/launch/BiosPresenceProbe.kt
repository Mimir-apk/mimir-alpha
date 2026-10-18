package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosCandidate
import com.gruning.mimir.rewrite.core.model.BiosCandidateKind
import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import java.util.Locale

// Under scoped storage listFiles() returns NULL even when exists() is true. That is precisely the
// "cannot read" case that must report UNKNOWN, never a false MISSING.
fun folderIsEnumerable(f: File?): Boolean =
    f != null && f.exists() && f.isDirectory && f.listFiles() != null

/** A cheap filename/size view; BIOS matching never reads file contents and never hashes. */
internal data class BiosProbeFile(
    val name: String,
    val sizeBytes: Long,
    val parentCanonicalPath: String? = null,
)

/** One enumerated direct or already-granted user-library folder. */
internal data class BiosFolderListing(
    val enumerable: Boolean,
    val files: List<BiosProbeFile> = emptyList(),
)

internal object BiosCandidateMatcher {
    private const val DUCKSTATION_BIOS_RELATIVE_FOLDER = "Android/data/com.github.stenzek.duckstation/files/bios"

    /**
     * DuckStation's BIOS folder as the Android user [userId] sees it (see [androidUserIdOf]). The checksum-import branch
     * compares a file's parent against this, so it must be the running user's folder: the owner's storage root
     * would never match a secondary user's import, and a genuine BIOS would read as absent.
     */
    fun duckStationBiosFolder(userId: Int = 0): String = "${primaryStorageRootFor(userId)}/$DUCKSTATION_BIOS_RELATIVE_FOLDER"

    private const val ONE_KIB = 1024L
    private const val ONE_MIB = 1024L * 1024L
    private const val EIGHT_MIB = 8L * ONE_MIB
    private val duckStationImportedName = Regex("[a-f0-9]{32}\\.bin")

    /**
     * Size BAND for a checksum-renamed import, not a single value.
     *
     * The legacy app pinned this to exactly 524,288 (a 512 KiB PS1 image). Device truth on an
     * RP5 disproved that: the real imported file is
     * `acf4730ceb38ac9d8c7d8e21f2614600.bin` at 4,194,304 bytes, so the exact check silently failed to
     * recognise a BIOS that is actually there — the precise false-negative this ticket exists to avoid.
     * The parent-folder + checksum-name pair is already strong evidence (the emulator only writes
     * imports there), so the size test is a sanity guard against unrelated files, not an identifier.
     */
    private const val CHECKSUM_IMPORT_MIN = 512L * ONE_KIB
    private const val CHECKSUM_IMPORT_MAX = EIGHT_MIB

    fun matches(
        platformId: PlatformId,
        candidate: BiosCandidate,
        file: BiosProbeFile,
        duckStationFolderCanonicalPath: String = File(duckStationBiosFolder()).canonicalPath,
    ): Boolean {
        val name = file.name.trim().lowercase(Locale.US)
        if (platformId == PlatformId.PS1 &&
            duckStationImportedName.matches(name) &&
            file.sizeBytes in CHECKSUM_IMPORT_MIN..CHECKSUM_IMPORT_MAX &&
            file.parentCanonicalPath == duckStationFolderCanonicalPath
        ) {
            return true
        }

        if (platformId == PlatformId.PS2 && !isPs2Candidate(name, file.sizeBytes)) return false
        return when (candidate.kind) {
            BiosCandidateKind.FILENAME -> globMatches(name, candidate.value.lowercase(Locale.US))
            BiosCandidateKind.EXTENSION -> name.substringAfterLast('.', "") == candidate.value.lowercase(Locale.US)
        }
    }

    internal fun isPs2Candidate(rawName: String, sizeBytes: Long): Boolean {
        val name = rawName.trim().lowercase(Locale.US)
        if (!name.endsWith(".bin") && !name.endsWith(".rom")) return false
        if (name.startsWith("scph") || name.contains("ps2") || name.contains("bios")) {
            return sizeBytes in ONE_MIB..EIGHT_MIB
        }
        if (name == "rom1.bin" || name == "rom2.bin" || name == "erom.bin") {
            return sizeBytes in (4L * ONE_KIB)..ONE_MIB
        }
        return false
    }

    private fun globMatches(value: String, pattern: String): Boolean {
        if ('*' !in pattern) return value == pattern
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(value)
    }
}

/**
 * Tiers 1, 2, 4, and 5. A readable user folder proves presence, never a direct-folder absence.
 *
 * `userId` is the Android user the process runs as (see [androidUserIdOf]); it only decides which user's DuckStation
 * folder a checksum-renamed import must sit in, and user 0, the owner, is the default.
 */
internal object BiosPresenceProbe {
    fun probe(
        platformId: PlatformId,
        candidate: BiosCandidate,
        directFolder: File?,
        userLibraryFolders: List<File>,
        userId: Int = 0,
    ): BiosPresence = probe(
        platformId = platformId,
        candidate = candidate,
        direct = directFolder.toListing(),
        userLibrary = userLibraryFolders.map { it.toListing() },
        userId = userId,
    )

    fun probe(
        platformId: PlatformId,
        candidate: BiosCandidate,
        direct: BiosFolderListing,
        userLibrary: List<BiosFolderListing>,
        userId: Int = 0,
    ): BiosPresence {
        fun matches(file: BiosProbeFile): Boolean = BiosCandidateMatcher.matches(
            platformId,
            candidate,
            file,
            duckStationFolderCanonicalPath = File(BiosCandidateMatcher.duckStationBiosFolder(userId)).canonicalPath,
        )
        if (direct.files.any(::matches)) {
            return BiosPresence.PRESENT
        }
        if (userLibrary.any { folder -> folder.enumerable && folder.files.any(::matches) }) {
            return BiosPresence.PRESENT
        }
        return if (direct.enumerable) BiosPresence.MISSING else BiosPresence.UNKNOWN
    }

    internal fun File?.toListing(): BiosFolderListing {
        if (!folderIsEnumerable(this)) return BiosFolderListing(enumerable = false)
        val files = this!!.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile }
            .map { file ->
                BiosProbeFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    parentCanonicalPath = runCatching { file.parentFile?.canonicalPath }.getOrNull(),
                )
            }
            .toList()
        return BiosFolderListing(enumerable = true, files = files)
    }
}
