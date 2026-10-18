package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchRecency
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.BiosProfileDao
import com.gruning.mimir.rewrite.core.storage.dao.EmulatorOverrideDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.dao.LaunchProofDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.mapper.toEmulatorIdOrNull
import com.gruning.mimir.rewrite.core.storage.mapper.toBiosProfile
import com.gruning.mimir.rewrite.core.storage.mapper.toEntity
import com.gruning.mimir.rewrite.core.storage.scan.ExternalStorageDocumentPath
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Room-backed launch data gatherers. Each is a thin adapter from a `core-storage` DAO to the exact
 * fact the launch pipeline needs, exposed as a `suspend` read (Room DAOs are suspend).
 *
 * These are NOT the readiness engine's `fun interface` ports directly — those ports are SYNCHRONOUS and
 * pure (so the engine stays unit-testable with fakes). The
 * [LaunchController] first calls these gatherers to snapshot the device facts, then hands the engine
 * simple in-memory providers over that snapshot. This keeps the seam between "async storage read" and
 * "pure decision" crisp.
 */

/** Resolves the exact launch locator for the chosen group MEMBER from the `games` table. */
class RoomLaunchLocatorGatherer(
    private val gameDao: GameDao,
    private val generatedM3uWriter: GeneratedM3uWriter? = null,
) {
    /**
     * Load the chosen member (or the group's default launch member) and pick the locator for
     * [preferredForm]. Returns null when no locator satisfies the form (→ MissingFile).
     *
     * A [GameId] is a dedup group (multi-disc / region / cross-storage), so resolving by group id alone
     * can launch the wrong disc — [launchMemberEntryKey] selects the exact member (native launch seam contract). Null lets us pick the group's primary/first-disc member.
     *
     * Locator selection follows the launch engine's handoff needs:
     *  - FILE_PATH → managed path, or a filesystem path derived from Android ExternalStorageProvider
     *    document URIs (`content://com.android.externalstorage.documents/.../document/<volume>:<path>`).
     *  - CONTENT_URI → the exact SAF document URI. A `content://` tree root is never a launch locator
     *    for DS/3DS-style content handoff; when the document URI is missing, return null (→ MissingFile).
     *  - EITHER → managed path first, else a content locator.
     *  - NONE → "" (open-emulator-only).
     */
    suspend fun resolve(
        gameId: GameId,
        launchMemberEntryKey: EntryKey?,
        preferredDisc: Int,
        preferredForm: LaunchLocatorForm,
    ): LaunchLocatorResult? {
        // A retained alias on an ejected volume is historical truth, not a launch candidate. The
        // identity reconciler normally promotes an available alias transactionally; this filter is the
        // final launch boundary for an in-flight detach or a legacy database awaiting reconciliation.
        val members = gameDao.membersOfGame(gameId.value)
            .filter { it.availability == "AVAILABLE" }
        if (members.isEmpty()) return null
        val member = chosenMember(members, launchMemberEntryKey, preferredDisc) ?: return null

        if (launchMemberEntryKey == null && preferredDisc <= 0 &&
            member.contentKind != "MULTI_DISC_PLAYLIST" &&
            (preferredForm == LaunchLocatorForm.FILE_PATH || preferredForm == LaunchLocatorForm.EITHER) &&
            !hasAmbiguousDiscVariants(members)
        ) {
            val discs = multiDiscLaunchMembers(members)
            val paths = discs.mapNotNull(::filePathForPlaylistMember)
            if (discs.size >= 2 && paths.size == discs.size) {
                generatedM3uWriter?.write(gameId.value, paths)?.let { generated ->
                    return LaunchLocatorResult(generated, LaunchLocatorForm.FILE_PATH)
                }
            }
        }
        return launchLocatorFrom(member, preferredForm)
    }

    /**
     * The file NAME of the member [resolve] would hand the emulator for the same arguments: the member's own physical
     * file, never the `.m3u` this class generates for a multi-disc launch (that name is made up, so it would say what
     * an emulator accepts of a playlist Mimir wrote rather than of the game's disc). An existing playlist the player owns
     * is itself a library member and is named as it is. Null when there is no such available member. A read only: it
     * writes nothing and builds no locator.
     */
    suspend fun launchMemberFileName(
        gameId: GameId,
        launchMemberEntryKey: EntryKey?,
        preferredDisc: Int,
    ): String? {
        val members = gameDao.membersOfGame(gameId.value)
            .filter { it.availability == "AVAILABLE" }
        val member = chosenMember(members, launchMemberEntryKey, preferredDisc) ?: return null
        return physicalFileName(member)
    }

    /**
     * Every file name Play could start for [gameId], the one [launchMemberFileName] names first. With a chosen member or disc
     * ([launchMemberEntryKey] or [preferredDisc] given) that is the one launch alone; with neither it is every available member of
     * the group, because Play can start any of them (a group may hold one file per region, and the player picks which). Same
     * rules as [launchMemberFileName]: the members' own names, never the generated playlist; a read only.
     */
    suspend fun launchMemberFileNames(
        gameId: GameId,
        launchMemberEntryKey: EntryKey?,
        preferredDisc: Int,
    ): List<String> {
        val members = gameDao.membersOfGame(gameId.value)
            .filter { it.availability == "AVAILABLE" }
        val chosen = chosenMember(members, launchMemberEntryKey, preferredDisc) ?: return emptyList()
        val tested = if (launchMemberEntryKey != null || preferredDisc > 0) {
            listOf(chosen)
        } else {
            listOf(chosen) + members.filter { it.entryKey != chosen.entryKey }
        }
        return tested.mapNotNull(::physicalFileName).distinct()
    }

    private fun physicalFileName(member: GameEntity): String? =
        member.relativePath.substringAfterLast('/').ifBlank { member.displayName }.takeIf { it.isNotBlank() }

    private fun chosenMember(members: List<GameEntity>, launchMemberEntryKey: EntryKey?, preferredDisc: Int): GameEntity? =
        when (launchMemberEntryKey) {
            null -> defaultLaunchMember(members, preferredDisc)
            else -> members.firstOrNull { it.entryKey == launchMemberEntryKey.value }
        }
}

/**
 * Existing playlist first; an explicit preferred disc next; otherwise the same semantic/physical
 * ordering as the Room query. Re-sorting at this launch boundary makes opaque resident-key codec
 * order incapable of steering a launch even when a test double or future DAO returns rows unsorted.
 */
internal fun defaultLaunchMember(members: List<GameEntity>, preferredDisc: Int): GameEntity? {
    if (preferredDisc > 0) {
        multiDiscLaunchMembers(members).firstOrNull { it.discNumber == preferredDisc }?.let { return it }
    }
    members.filter { it.contentKind == "MULTI_DISC_PLAYLIST" }
        .minWithOrNull(PHYSICAL_LAUNCH_MEMBER_ORDER)
        ?.let { return it }
    return members.minWithOrNull(DEFAULT_LAUNCH_MEMBER_ORDER)
}

private val PHYSICAL_LAUNCH_MEMBER_ORDER =
    compareBy<GameEntity>(
        GameEntity::sourceRootUri,
        GameEntity::relativePath,
        GameEntity::entryKey,
    )

private val DEFAULT_LAUNCH_MEMBER_ORDER =
    compareBy<GameEntity>(
        { if (it.isPrimaryMember) 0 else 1 },
        { if (it.discNumber > 0) 0 else 1 },
        { if (it.discNumber > 0) it.discNumber else 0 },
        GameEntity::displayName,
        GameEntity::sourceRootUri,
        GameEntity::relativePath,
        GameEntity::entryKey,
    )

internal fun launchLocatorFrom(entity: GameEntity, form: LaunchLocatorForm): LaunchLocatorResult? {
    val managed = entity.managedPath?.takeIf { it.isNotBlank() }
    val contentLocator = entity.sourceDocumentUri?.takeIf { it.startsWith("content://") }
    val externalStoragePath = contentLocator?.let(::externalStoragePathFromDocumentUri)

    return when (form) {
        LaunchLocatorForm.FILE_PATH -> (managed ?: externalStoragePath)
            ?.let { LaunchLocatorResult(it, LaunchLocatorForm.FILE_PATH) }

        // The exact SAF document when we have one. When we do NOT -- the game was indexed by path, which
        // is what happens whenever Mimir has all-files access and no persisted tree grant -- fall back to
        // that path rather than refusing. Returning null here is what made every Switch title on the
        // tested Odin die as MissingFile while PS1/PS2/GameCube launched: their seeds are file-path
        // forms, the Switch seeds are CONTENT_URI, and the only tree grant on the device belonged to a
        // different package. The file was readable the whole time.
        //
        // A tree ROOT is still never a locator -- that is a different question and still answered no.
        // The dispatcher turns this path into a `content://` URI through Mimir's own FileProvider
        // (LaunchPathStrategy.SHAREABLE_URI_DATA), the same handoff Dolphin, AetherSX2 and Cemu use.
        LaunchLocatorForm.CONTENT_URI ->
            contentLocator?.let { LaunchLocatorResult(it, LaunchLocatorForm.CONTENT_URI) }
                ?: (managed ?: externalStoragePath)
                    ?.let { LaunchLocatorResult(it, LaunchLocatorForm.FILE_PATH) }

        LaunchLocatorForm.EITHER -> when {
            managed != null -> LaunchLocatorResult(managed, LaunchLocatorForm.FILE_PATH)
            externalStoragePath != null -> LaunchLocatorResult(externalStoragePath, LaunchLocatorForm.FILE_PATH)
            contentLocator != null -> LaunchLocatorResult(contentLocator, LaunchLocatorForm.CONTENT_URI)
            else -> null
        }

        LaunchLocatorForm.NONE -> LaunchLocatorResult("", LaunchLocatorForm.NONE)
    }
}

/**
 * The `/storage/...` path a SAF document uri names. One function in `core-storage`, so the string recorded here as a launch
 * `locator` is the very string a launch-history carry or split looks for (see `GameIdentityDao.launchLocatorsOf`).
 */
internal fun externalStoragePathFromDocumentUri(uri: String): String? = ExternalStorageDocumentPath.pathOf(uri)

/**
 * The per-game Open-With override lookup, backed by the `emulator_overrides` table . An empty stored value is an explicit Auto reset → null.
 */
class RoomOverrideGatherer(
    private val overrideDao: EmulatorOverrideDao,
    private val gameIdentityDao: GameIdentityDao? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The stored override for [gameId], or null (Auto / no override / explicit reset). */
    suspend fun resolve(gameId: GameId): EmulatorId? =
        (gameIdentityDao?.emulatorOverrideForCurrent(gameId.value) ?: overrideDao.forGame(gameId.value))
            ?.toEmulatorIdOrNull()

    /** Set the per-game "Open with…" override to [emulatorId]. */
    suspend fun set(gameId: GameId, emulatorId: EmulatorId) {
        val identityDao = gameIdentityDao
        if (identityDao != null) {
            identityDao.setEmulatorOverrideForCurrent(gameId.value, emulatorId.value, clock())
        } else {
            overrideDao.set(
                com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity(
                    gameId = gameId.value,
                    emulatorId = emulatorId.value,
                    setAtMillis = clock(),
                ),
            )
        }
    }

    /** Clear the override → reset to Auto (default-to-installed). */
    suspend fun clear(gameId: GameId) {
        gameIdentityDao?.clearEmulatorOverrideForCurrent(gameId.value)
            ?: overrideDao.clear(gameId.value)
    }
}

/**
 * BIOS presence backed by the `bios_profiles` table. Launch reads only the exact durable scope for the
 * resolved emulator identity: `(platformId, emulatorScopeKey, packageName)`. Legacy platform-wide rows,
 * empty migrated scopes, and other packages sharing the same emulator ID are never borrowed.
 */
class RoomBiosGatherer(
    private val biosDao: BiosProfileDao,
) {
    suspend fun resolve(
        platformId: PlatformId,
        emulatorId: EmulatorId,
        packageName: String,
    ): List<BiosProfile> =
        biosDao.forExactScope(platformId.value, emulatorId.value, packageName)
            .map { it.toBiosProfile() }
}

/** Persists a [LaunchProof] to the `launch_proofs` table (durable, locator-scoped failure lifetime). */
class RoomLaunchProofRecorder(
    private val proofDao: LaunchProofDao,
    private val gameIdentityDao: GameIdentityDao? = null,
) {
    /** Persist the proof row. */
    suspend fun record(proof: LaunchProof) {
        val entity = proof.toEntity()
        gameIdentityDao?.recordLaunchProofForCurrent(entity) ?: proofDao.record(entity)
    }
}

/** Room adapter for explicit Android-package proofs and their successful Last Played projection. */
class RoomAndroidGameLaunchProofStore(
    private val proofDao: LaunchProofDao,
) : AndroidGameLaunchProofStore {
    override suspend fun record(proof: AndroidGameLaunchProof) {
        proofDao.record(proof.toEntity())
    }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<AndroidGameLaunchRecency>> =
        proofDao.recentlyPlayedAndroidGames(limit).map { rows ->
            rows.mapNotNull { row ->
                runCatching { AndroidPackageName(row.androidPackageName) }.getOrNull()?.let { packageName ->
                    AndroidGameLaunchRecency(
                        packageName = packageName,
                        lastPlayedMillis = row.lastPlayedMillis,
                    )
                }
            }
        }
}
