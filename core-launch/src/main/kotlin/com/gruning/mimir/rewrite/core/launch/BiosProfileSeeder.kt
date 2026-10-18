package com.gruning.mimir.rewrite.core.launch

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.model.BiosCandidateRegistry
import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.BiosProfileDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.mapper.toEntity
import java.io.File
import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BiosLibraryRoot(val rootUri: String, val locationKind: String)

/**
 * Marks a root that came from the tier-3 BIOS grant rather than the library. Distinct from any
 * `locationKind` the scanner writes, so a grep can always tell the two apart at a glance.
 */
internal const val BiosGrantLocationKind: String = "bios-grant"

/**
 * Enumerates one already-granted library root (tier 2). Behind a seam because the SAF branch needs a
 * `Context`, and a `Context` in the seeder's constructor forced its unit test toward
 * `android.test.mock.MockContext` — which does not exist on the JVM test classpath. The seeder itself is
 * pure orchestration + filename/size matching, so it stays testable without Android.
 */
internal fun interface BiosLibraryRootLister {
    fun listing(root: BiosLibraryRoot): BiosFolderListing
}

/**
 * Immutable exact-scope BIOS facts produced by [BiosProfileSeeder.ensureSeeded]. Retains emulator/package
 * identity on every profile row; callers must treat returned instances as read-only (lists are defensive copies).
 */
internal class BiosSeedSnapshot(
    val platformId: PlatformId,
    val emulatorId: EmulatorId?,
    val packageName: String?,
    val requiresBios: Boolean,
    profiles: List<BiosProfile>,
) {
    val profiles: List<BiosProfile> = Collections.unmodifiableList(profiles.map { it.copy() })

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BiosSeedSnapshot) return false
        return platformId == other.platformId &&
            emulatorId == other.emulatorId &&
            packageName == other.packageName &&
            requiresBios == other.requiresBios &&
            profiles == other.profiles
    }

    override fun hashCode(): Int {
        var result = platformId.hashCode()
        result = 31 * result + (emulatorId?.hashCode() ?: 0)
        result = 31 * result + (packageName?.hashCode() ?: 0)
        result = 31 * result + requiresBios.hashCode()
        result = 31 * result + profiles.hashCode()
        return result
    }
}

internal interface BiosSeedPersistence {
    suspend fun platformsWithGames(): List<PlatformId>
    suspend fun platformForGame(gameId: GameId): PlatformId?
    suspend fun libraryRoots(): List<BiosLibraryRoot>

    /**
     * Tier 3: folders the user granted **specifically for BIOS**, kept apart from
     * [libraryRoots] on purpose.
     *
     * These must never reach `sourceRootDao`. A library source root is a place Mimir SCANS FOR GAMES, and a
     * BIOS folder indexed as one would put `scph5501.bin` on the shelf as a game. Tier 3 is read-only
     * evidence for presence and nothing else — same enumeration, different meaning.
     */
    suspend fun biosGrantRoots(): List<BiosLibraryRoot> = emptyList()

    suspend fun persist(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
        profiles: List<BiosProfile>,
    )
}

internal class RoomBiosSeedPersistence(
    private val biosDao: BiosProfileDao,
    private val gameDao: GameDao,
    private val sourceRootDao: SourceRootDao,
    /**
     * Tier-3 BIOS grants, supplied rather than stored in Room. A persisted SAF tree URI is device-local
     * setup, not library data — putting it in a Room table would need a migration to store one string and
     * would sit next to the game rows it must never become. The app layer owns the store.
     */
    private val biosGrantUris: suspend () -> List<String> = { emptyList() },
) : BiosSeedPersistence {
    override suspend fun platformsWithGames(): List<PlatformId> =
        gameDao.platformsWithVisibleGames().map(::PlatformId)

    override suspend fun platformForGame(gameId: GameId): PlatformId? =
        gameDao.platformIdForGame(gameId.value)?.let(::PlatformId)

    override suspend fun libraryRoots(): List<BiosLibraryRoot> =
        sourceRootDao.allOnce().map { BiosLibraryRoot(it.rootUri, it.locationKind) }

    override suspend fun biosGrantRoots(): List<BiosLibraryRoot> =
        biosGrantUris().map { BiosLibraryRoot(it, BiosGrantLocationKind) }

    override suspend fun persist(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
        profiles: List<BiosProfile>,
    ) {
        biosDao.replaceExactScope(
            platformId = platformId.value,
            emulatorScopeKey = emulatorId.value,
            profiles = profiles.map { profile ->
                profile.copy(
                    platformId = platformId,
                    emulatorId = emulatorId,
                    packageName = packageName,
                ).toEntity()
            },
        )
    }
}

/**
 * Memoized BIOS WRITE side. It runs only at explicit data-change seams and lazy readiness reads; never
 * from scanning or row mapping. Presence work is filename/size-only and local.
 */
internal class BiosProfileSeeder(
    private val rootLister: BiosLibraryRootLister,
    private val persistence: BiosSeedPersistence,
    private val emulators: InstalledEmulatorProvider,
    private val platformDefault: suspend (PlatformId) -> EmulatorId?,
    /**
     * The Android user this process runs as (see [androidUserIdOf]): an emulator's BIOS folder is under that user's
     * `/storage/emulated/<id>`, so the owner's `0` would probe a folder a secondary user or work profile cannot see.
     * The default is the owner, so a caller without an Android runtime (a host test) is unchanged.
     */
    private val androidUserId: Int = 0,
) {
    /**
     * Test-only publication gate: invoked after snapshot computation and immediately before the final
     * revision check + persist/cache. Lets adversarial tests widen the stale-publication window without
     * weakening production ordering.
     */
    internal var beforePersistGate: (suspend (PlatformId) -> Unit)? = null
    private data class MemoKey(
        val platformId: PlatformId,
        val emulatorId: EmulatorId,
        val packageName: String,
        val libraryRevision: Long,
        val assignmentGeneration: Long,
    )

    private val mutex = Mutex()
    private val snapshotCache = mutableMapOf<MemoKey, BiosSeedSnapshot>()
    private var libraryRevision = 0L
    private val assignmentGenerationByPlatform = mutableMapOf<PlatformId, Long>()

    /** Lazy-first-read trigger for the exact emulator the readiness engine resolved. */
    suspend fun ensureSeeded(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
    ): BiosSeedSnapshot {
        while (true) {
            val key = mutex.withLock {
                MemoKey(
                    platformId = platformId,
                    emulatorId = emulatorId,
                    packageName = packageName,
                    libraryRevision = libraryRevision,
                    assignmentGeneration = assignmentGenerationLocked(platformId),
                )
            }
            mutex.withLock { snapshotCache[key] }?.let { return it }
            val published = seedPlatform(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                userLibrary = libraryListings(),
                expectedLibraryRevision = key.libraryRevision,
                expectedAssignmentGeneration = key.assignmentGeneration,
            )
            if (published != null) return published
        }
    }

    /** Real library-revision trigger, called after (not inside) a successful scan and on initial load. */
    suspend fun libraryRevisionChanged() {
        val libraryRev = mutex.withLock {
            libraryRevision += 1L
            evictStaleLibraryRevisionLocked()
            libraryRevision
        }
        val listings = libraryListings()
        for (platformId in persistence.platformsWithGames()) {
            val expectedAssignmentGeneration = mutex.withLock {
                assignmentGenerationLocked(platformId)
            }
            val profile = resolveProfile(platformId, platformDefault(platformId)) ?: continue
            seedPlatform(
                platformId = platformId,
                emulatorId = profile.id,
                packageName = profile.packageName,
                userLibrary = listings,
                expectedLibraryRevision = libraryRev,
                expectedAssignmentGeneration = expectedAssignmentGeneration,
            )
        }
    }

    /** Per-game Open-With trigger. The game lookup keeps filesystem work out of Room row projection. */
    suspend fun emulatorAssignmentChanged(gameId: GameId, emulatorId: EmulatorId?) {
        val platformId = persistence.platformForGame(gameId) ?: return
        emulatorAssignmentChanged(platformId, emulatorId)
    }

    /** Per-platform default-emulator trigger. */
    suspend fun emulatorAssignmentChanged(platformId: PlatformId, emulatorId: EmulatorId?) {
        val (libraryRev, assignmentGen) = mutex.withLock {
            val generation = bumpAssignmentGenerationLocked(platformId)
            libraryRevision to generation
        }
        val profile = resolveProfile(platformId, emulatorId ?: platformDefault(platformId)) ?: return
        seedPlatform(
            platformId = platformId,
            emulatorId = profile.id,
            packageName = profile.packageName,
            userLibrary = libraryListings(),
            expectedLibraryRevision = libraryRev,
            expectedAssignmentGeneration = assignmentGen,
        )
    }

    private suspend fun seedPlatform(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
        userLibrary: List<BiosFolderListing>,
        expectedLibraryRevision: Long,
        expectedAssignmentGeneration: Long,
    ): BiosSeedSnapshot? {
        val requiresBios = EmulatorSeedRegistry.requiresBios(packageName)
        val emulatorSeed = EmulatorSeedRegistry.biosSeedForPackage(packageName)
        val catalog = BiosCandidateRegistry.forPlatform(platformId)
        if (catalog == null) {
            return memoizeEmptyCatalogSnapshot(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                requiresBios = requiresBios,
                expectedLibraryRevision = expectedLibraryRevision,
                expectedAssignmentGeneration = expectedAssignmentGeneration,
            )
        }

        val cached = mutex.withLock {
            if (!revisionsStillMatch(platformId, expectedLibraryRevision, expectedAssignmentGeneration)) {
                return null
            }
            val key = memoKeyLocked(platformId, emulatorId, packageName)
            snapshotCache[key]
        }
        if (cached != null) return cached

        val directFolder = emulatorSeed?.biosFolderPath(androidUserId)?.let(::File)
        val directListing = BiosPresenceProbe.run { directFolder.toListing() }
        // Roll ALTERNATIVES up before persisting: one row per group, not per filename. A group is
        // PRESENT if any member is; MISSING only when every member is definitively missing; UNKNOWN
        // otherwise (so an unreadable folder can never manufacture a false negative). Without this the
        // readiness engine — which blocks when ANY required row is MISSING — reported "needs BIOS" for
        // a working PS1 setup, because the other two region variants were absent by definition.
        val profiles = catalog.candidates
            .groupBy { it.groupKey }
            .map { (_, members) ->
                val presences = members.map {
                    BiosPresenceProbe.probe(platformId, it, directListing, userLibrary, androidUserId)
                }
                val presence = when {
                    presences.any { it == BiosPresence.PRESENT } -> BiosPresence.PRESENT
                    presences.isNotEmpty() && presences.all { it == BiosPresence.MISSING } ->
                        BiosPresence.MISSING
                    else -> BiosPresence.UNKNOWN
                }
                // Name the group by the file a user should actually place: its first required member.
                val canonical = members.firstOrNull { it.required } ?: members.first()
                BiosProfile(
                    platformId = platformId,
                    fileName = canonical.fileName,
                    required = members.any { it.required },
                    presence = presence,
                    emulatorId = emulatorId,
                    packageName = packageName,
                )
            }

        beforePersistGate?.invoke(platformId)

        mutex.lock()
        try {
            if (!revisionsStillMatch(platformId, expectedLibraryRevision, expectedAssignmentGeneration)) {
                return null
            }
            val key = memoKeyLocked(platformId, emulatorId, packageName)
            snapshotCache[key]?.let { return it }
            val snapshot = snapshotFor(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                requiresBios = requiresBios,
                profiles = profiles,
            )
            persistence.persist(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                profiles = profiles,
            )
            snapshotCache[key] = snapshot
            return snapshot
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun memoizeEmptyCatalogSnapshot(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
        requiresBios: Boolean,
        expectedLibraryRevision: Long,
        expectedAssignmentGeneration: Long,
    ): BiosSeedSnapshot? {
        mutex.lock()
        try {
            if (!revisionsStillMatch(platformId, expectedLibraryRevision, expectedAssignmentGeneration)) {
                return null
            }
            val key = memoKeyLocked(platformId, emulatorId, packageName)
            snapshotCache[key]?.let { return it }
            val snapshot = snapshotFor(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                requiresBios = requiresBios,
                profiles = emptyList(),
            )
            persistence.persist(
                platformId = platformId,
                emulatorId = emulatorId,
                packageName = packageName,
                profiles = emptyList(),
            )
            snapshotCache[key] = snapshot
            return snapshot
        } finally {
            mutex.unlock()
        }
    }

    private fun assignmentGenerationLocked(platformId: PlatformId): Long =
        assignmentGenerationByPlatform.getOrDefault(platformId, 0L)

    private fun bumpAssignmentGenerationLocked(platformId: PlatformId): Long {
        val next = assignmentGenerationLocked(platformId) + 1L
        assignmentGenerationByPlatform[platformId] = next
        snapshotCache.keys.removeAll { key ->
            key.platformId == platformId && key.assignmentGeneration != next
        }
        return next
    }

    private fun memoKeyLocked(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
    ): MemoKey = MemoKey(
        platformId = platformId,
        emulatorId = emulatorId,
        packageName = packageName,
        libraryRevision = libraryRevision,
        assignmentGeneration = assignmentGenerationLocked(platformId),
    )

    private fun revisionsStillMatch(
        platformId: PlatformId,
        expectedLibraryRevision: Long,
        expectedAssignmentGeneration: Long,
    ): Boolean =
        libraryRevision == expectedLibraryRevision &&
            assignmentGenerationLocked(platformId) == expectedAssignmentGeneration

    private fun evictStaleLibraryRevisionLocked() {
        snapshotCache.keys.removeAll { it.libraryRevision != libraryRevision }
    }

    private fun snapshotFor(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
        requiresBios: Boolean,
        profiles: List<BiosProfile>,
    ): BiosSeedSnapshot = BiosSeedSnapshot(
        platformId = platformId,
        emulatorId = emulatorId,
        packageName = packageName,
        requiresBios = requiresBios,
        profiles = profiles,
    )

    private suspend fun resolveProfile(
        platformId: PlatformId,
        preferred: EmulatorId?,
    ): EmulatorProfile? = resolveInstalledEmulator(
        emulators.candidatesFor(platformId),
        preferred,
        platformId,
    )

    /**
     * Tier 2 (already-granted library roots) plus tier 3 (the BIOS-specific grant), enumerated the same way.
     *
     * Order is deliberate: library roots first, so the common case short-circuits before touching a SAF tree
     * the user may not have granted. Both are only ever evidence FOR presence — [BiosPresenceProbe] still
     * refuses to conclude MISSING from a user folder, so adding tier 3 can turn UNKNOWN or MISSING into
     * PRESENT and can never do the reverse.
     */
    private suspend fun libraryListings(): List<BiosFolderListing> =
        (persistence.libraryRoots() + persistence.biosGrantRoots()).map(rootLister::listing)
}

/**
 * Android implementation of tier 2: a `file://`/plain path is enumerated directly; an already-granted
 * `content://` library tree is read through SAF. Existing LIBRARY grants only — slice 2's new
 * BIOS-specific SAF grant is deliberately absent.
 */
internal class AndroidBiosLibraryRootLister(private val context: Context) : BiosLibraryRootLister {
    override fun listing(root: BiosLibraryRoot): BiosFolderListing {
        if (root.rootUri.startsWith("content://")) return listDocumentTree(Uri.parse(root.rootUri))
        val path = if (root.rootUri.startsWith("file://")) Uri.parse(root.rootUri).path else root.rootUri
        return BiosPresenceProbe.run { path?.let(::File).toListing() }
    }

    private fun listDocumentTree(treeUri: Uri): BiosFolderListing = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = context.contentResolver.query(children, projection, null, null, null)
            ?: return@runCatching BiosFolderListing(enumerable = false)
        cursor.use {
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val files = buildList {
                while (it.moveToNext()) {
                    val mime = if (mimeIndex >= 0 && !it.isNull(mimeIndex)) it.getString(mimeIndex) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || nameIndex < 0 || it.isNull(nameIndex)) continue
                    add(
                        BiosProbeFile(
                            name = it.getString(nameIndex),
                            sizeBytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L,
                        ),
                    )
                }
            }
            BiosFolderListing(enumerable = true, files = files)
        }
    }.getOrElse { BiosFolderListing(enumerable = false) }
}
