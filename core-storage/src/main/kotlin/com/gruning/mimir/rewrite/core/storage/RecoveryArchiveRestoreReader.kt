package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.ProfileId
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MANIFEST_HASH_PATH
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MANIFEST_PATH
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_JSONL_LINE_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RETAINED_RECORDS
import com.gruning.mimir.rewrite.core.model.recovery.RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveCancelledException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveIdentityIndex
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveRestoreSnapshot
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchiveTablesV1
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedEmulatorOverride
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedFavorite
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedGameEvidence
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedHidden
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryArchivedShelfItem
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRetainedRecordCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreRetainedTextCapException
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreSchemaException
import com.gruning.mimir.rewrite.core.model.recovery.RecoverySourceRootHint
import com.gruning.mimir.rewrite.core.model.recovery.ValidatedRecoveryArchive
import com.gruning.mimir.rewrite.core.model.recovery.validateCanonicalRecoveryJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Typed two-pass projection for Recovery Archive V1 restore.
 *
 * Pass 1 reads desired owner-state rows plus archive-space aliases/groups and source-root hints.
 * Pass 2 reads [games] evidence only for the normalized ids referenced by pass 1.
 *
 * [read] always runs [RecoveryArchiveV1SemanticValidator] on the structural input before either
 * projection pass; structural validity alone never projects.
 */
object RecoveryArchiveRestoreReader {
    private val json = Json {
        isLenient = false
        explicitNulls = false
    }

    fun read(
        structural: ValidatedRecoveryArchive,
        shouldContinue: () -> Boolean = { true },
    ): Result<RecoveryArchiveRestoreSnapshot> = runCatching {
        val schemaVersion = structural.manifest.schemaVersion
        // The restore reader has two schema checks before delegation to the semantic validator.
        // With the exporter and validator's two checks, that is five checks of the same bounds.
        // Leaving these two at 12 would refuse a valid schema-13 archive on restore even after
        // export succeeded. Read the shared bounds rather than restating them;
        // RecoveryArchiveSchemaContractTest scans the source to prevent drift.
        when {
            schemaVersion > RECOVERY_SCHEMA_MAX_VERSION_V1 -> throw RecoveryRestoreSchemaException(
                "Recovery archive schema $schemaVersion is newer than this reader supports.",
            )
            schemaVersion !in RECOVERY_SCHEMA_MIN_VERSION_V1..RECOVERY_SCHEMA_MAX_VERSION_V1 ->
                throw RecoveryRestoreSchemaException(
                    "Recovery Archive V1 restore requires Room schema " +
                        "$RECOVERY_SCHEMA_MIN_VERSION_V1 through $RECOVERY_SCHEMA_MAX_VERSION_V1.",
                )
        }

        RecoveryArchiveV1SemanticValidator.validate(structural, shouldContinue).getOrThrow()

        val budget = ProjectionBudget()
        val passOne = readPassOne(structural, shouldContinue, budget)
        passOne.enforceOwnerStateCap()
        val gameEvidence = readPassTwo(
            structural = structural,
            neededGameIds = passOne.neededNormalizedGameIds,
            platformLabels = passOne.platformLabels,
            shouldContinue = shouldContinue,
            budget = budget,
        )
        RecoveryArchiveRestoreSnapshot(
            schemaVersion = schemaVersion,
            favorites = passOne.favorites,
            hidden = passOne.hidden,
            emulatorOverrides = passOne.emulatorOverrides,
            shelfItems = passOne.shelfItems,
            identityIndex = passOne.identityIndex,
            gameEvidence = gameEvidence,
            sourceRootHints = passOne.sourceRootHints,
        )
    }

    private data class PassOneProjection(
        val favorites: List<RecoveryArchivedFavorite>,
        val hidden: List<RecoveryArchivedHidden>,
        val emulatorOverrides: List<RecoveryArchivedEmulatorOverride>,
        val shelfItems: List<RecoveryArchivedShelfItem>,
        val identityIndex: RecoveryArchiveIdentityIndex,
        val sourceRootHints: List<RecoverySourceRootHint>,
        val platformLabels: Map<String, String>,
        val neededNormalizedGameIds: Set<String>,
        val ownerStateCount: Int,
    ) {
        fun enforceOwnerStateCap() {
            if (ownerStateCount > RECOVERY_MAX_RESTORE_RECORDS) {
                throw RecoveryRestoreRecordCapException(ownerStateCount)
            }
        }
    }

    private class ProjectionBudget {
        private var retainedRecordCount: Int = 0
        private var retainedTextBytes: Long = 0
        private var ownerStateCount: Int = 0

        fun trackOwnerStateRow(vararg textFields: String) {
            trackRetainedRow(*textFields)
            val next = ownerStateCount + 1
            if (next > RECOVERY_MAX_RESTORE_RECORDS) {
                throw RecoveryRestoreRecordCapException(next)
            }
            ownerStateCount = next
        }

        fun trackRetainedRow(vararg textFields: String) {
            val rowTextBytes = textFields.sumOf { utf8ByteLength(it) }
            val nextRecords = retainedRecordCount + 1
            if (nextRecords > RECOVERY_MAX_RESTORE_RETAINED_RECORDS) {
                throw RecoveryRestoreRetainedRecordCapException(nextRecords)
            }
            val nextTextBytes = retainedTextBytes + rowTextBytes
            if (nextTextBytes > RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES) {
                throw RecoveryRestoreRetainedTextCapException(nextTextBytes)
            }
            retainedRecordCount = nextRecords
            retainedTextBytes = nextTextBytes
        }

        fun replaceRetainedRow(priorTextFields: Array<out String>, newTextFields: Array<out String>) {
            val priorBytes = priorTextFields.sumOf { utf8ByteLength(it) }
            val newBytes = newTextFields.sumOf { utf8ByteLength(it) }
            val projectedBytes = retainedTextBytes - priorBytes + newBytes
            if (projectedBytes > RECOVERY_MAX_RESTORE_RETAINED_TEXT_BYTES) {
                throw RecoveryRestoreRetainedTextCapException(projectedBytes)
            }
            retainedTextBytes = projectedBytes
        }

        fun ownerStateCount(): Int = ownerStateCount
    }

    private fun readPassOne(
        structural: ValidatedRecoveryArchive,
        shouldContinue: () -> Boolean,
        budget: ProjectionBudget,
    ): PassOneProjection {
        val favorites = mutableListOf<RecoveryArchivedFavorite>()
        val hidden = mutableListOf<RecoveryArchivedHidden>()
        val overrides = mutableListOf<RecoveryArchivedEmulatorOverride>()
        val shelfItems = mutableListOf<RecoveryArchivedShelfItem>()
        val aliasToLogical = linkedMapOf<String, String>()
        val logicalToCurrent = linkedMapOf<String, String>()
        val sourceRootHints = mutableListOf<RecoverySourceRootHint>()
        val platformLabels = linkedMapOf<String, String>()
        val neededGameIds = linkedSetOf<String>()

        ZipInputStream(
            BufferedInputStream(FileInputStream(structural.archive), 64 * 1024),
        ).use { zip ->
            checkRestoreContinue(shouldContinue)
            requireEntry(zip, RECOVERY_MANIFEST_PATH)
            drainEntry(zip, shouldContinue)
            zip.closeEntry()
            requireEntry(zip, RECOVERY_MANIFEST_HASH_PATH)
            drainEntry(zip, shouldContinue)
            zip.closeEntry()
            structural.manifest.entries.forEach { expected ->
                checkRestoreContinue(shouldContinue)
                requireEntry(zip, expected.path)
                when (expected.path) {
                    "db/favorites.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val gameId = row.requireText("gameId")
                        budget.trackOwnerStateRow(gameId)
                        favorites += RecoveryArchivedFavorite(
                            archivedGameId = gameId,
                            favoritedAtMillis = row.requireLong("favoritedAtMillis"),
                        )
                    }
                    "db/hidden_items.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val isHidden = row.requireBoolean("hidden")
                        if (isHidden) {
                            val gameId = row.requireText("gameId")
                            budget.trackOwnerStateRow(gameId)
                            hidden += RecoveryArchivedHidden(
                                archivedGameId = gameId,
                                hidden = true,
                                hiddenAtMillis = row.requireLong("hiddenAtMillis"),
                            )
                        }
                    }
                    "db/emulator_overrides.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val gameId = row.requireText("gameId")
                        val emulatorId = row.requireText("emulatorId")
                        budget.trackOwnerStateRow(gameId, emulatorId)
                        overrides += RecoveryArchivedEmulatorOverride(
                            archivedGameId = gameId,
                            emulatorId = emulatorId,
                            setAtMillis = row.requireLong("setAtMillis"),
                        )
                    }
                    "db/home_shelf_items.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val profileId = row.requireText("profileId")
                        val itemKind = row.requireText("itemKind")
                        val itemId = row.requireText("itemId")
                        budget.trackOwnerStateRow(profileId, itemKind, itemId)
                        shelfItems += RecoveryArchivedShelfItem(
                            profileId = profileId,
                            itemKind = itemKind,
                            itemId = itemId,
                            rank = row.requireLong("rank"),
                            addedAtMillis = row.requireLong("addedAtMillis"),
                        )
                    }
                    "db/game_id_aliases.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val oldGameId = row.requireText("oldGameId")
                        val logicalGameId = row.requireText("logicalGameId")
                        budget.trackRetainedRow(oldGameId, logicalGameId)
                        aliasToLogical[oldGameId] = logicalGameId
                    }
                    "db/logical_game_groups.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val logicalGameId = row.requireText("logicalGameId")
                        val currentGameId = row.requireText("currentGameId")
                        budget.trackRetainedRow(logicalGameId, currentGameId)
                        logicalToCurrent[logicalGameId] = currentGameId
                    }
                    "db/source_roots.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val friendlyLabel = row.requireText("friendlyLabel")
                        budget.trackRetainedRow(friendlyLabel)
                        sourceRootHints += RecoverySourceRootHint(
                            friendlyLabel = friendlyLabel,
                            isPrimary = row.requireBoolean("isPrimarySource"),
                        )
                    }
                    "db/platforms.jsonl" -> readTable(zip, shouldContinue) { row ->
                        val platformId = row.requireText("platformId")
                        val displayName = row.requireText("displayName")
                        budget.trackRetainedRow(platformId, displayName)
                        platformLabels[platformId] = displayName
                    }
                    "db/bios_profiles.jsonl" -> drainEntry(zip, shouldContinue)
                    RecoveryArchiveTablesV1.COMPATIBILITY_BUNDLE_PATH ->
                        RecoveryCompatibilityBundleValidator.validate(zip, shouldContinue)
                    else -> drainEntry(zip, shouldContinue)
                }
                zip.closeEntry()
            }
            if (zip.nextEntry != null) {
                throw RecoveryArchiveException("Recovery archive contains an unlisted restore payload.")
            }
        }

        val identityIndex = RecoveryArchiveIdentityIndex(
            aliasToLogical = aliasToLogical.toMap(),
            logicalToCurrent = logicalToCurrent.toMap(),
        )
        fun trackEmulated(gameId: String) {
            neededGameIds += identityIndex.normalizeArchivedGameId(gameId)
        }
        favorites.forEach { trackEmulated(it.archivedGameId) }
        hidden.filter(RecoveryArchivedHidden::hidden).forEach { trackEmulated(it.archivedGameId) }
        overrides.forEach { trackEmulated(it.archivedGameId) }
        shelfItems
            .filter { it.profileId == ProfileId.LOCAL.value && it.itemKind == "EMULATED_GAME" }
            .forEach { trackEmulated(it.itemId) }

        return PassOneProjection(
            favorites = favorites,
            hidden = hidden,
            emulatorOverrides = overrides,
            shelfItems = shelfItems,
            identityIndex = identityIndex,
            sourceRootHints = sourceRootHints,
            platformLabels = platformLabels,
            neededNormalizedGameIds = neededGameIds,
            ownerStateCount = budget.ownerStateCount(),
        )
    }

    private fun readPassTwo(
        structural: ValidatedRecoveryArchive,
        neededGameIds: Set<String>,
        platformLabels: Map<String, String>,
        shouldContinue: () -> Boolean,
        budget: ProjectionBudget,
    ): Map<String, RecoveryArchivedGameEvidence> {
        if (neededGameIds.isEmpty()) return emptyMap()
        val selected = linkedMapOf<String, CandidateGameRow>()
        ZipInputStream(
            BufferedInputStream(FileInputStream(structural.archive), 64 * 1024),
        ).use { zip ->
            checkRestoreContinue(shouldContinue)
            requireEntry(zip, RECOVERY_MANIFEST_PATH)
            drainEntry(zip, shouldContinue)
            zip.closeEntry()
            requireEntry(zip, RECOVERY_MANIFEST_HASH_PATH)
            drainEntry(zip, shouldContinue)
            zip.closeEntry()
            structural.manifest.entries.forEach { expected ->
                checkRestoreContinue(shouldContinue)
                requireEntry(zip, expected.path)
                if (expected.path == "db/games.jsonl") {
                    readTable(zip, shouldContinue) { row ->
                        val gameId = row.requireText("gameId")
                        if (gameId !in neededGameIds) return@readTable
                        val candidate = CandidateGameRow(
                            gameId = gameId,
                            entryKey = row.requireText("entryKey"),
                            sourceRootUri = row.optionalText("sourceRootUri"),
                            platformId = row.requireText("platformId"),
                            displayName = row.requireText("displayName"),
                            title = row.requireText("title"),
                            canonicalTitle = row.optionalText("canonicalTitle"),
                            duplicateGroupKind = row.optionalText("duplicateGroupKind")
                                ?.let { stored ->
                                    DuplicateGroupKind.entries.firstOrNull { it.name == stored }
                                },
                            sizeBytes = row.requireLong("sizeBytes"),
                            contentHash = row.optionalText("contentHash"),
                            serial = row.optionalText("serial"),
                            relativePath = row.requireText("relativePath"),
                            availability = row.requireText("availability"),
                            isPrimaryMember = row.requireBoolean("isPrimaryMember"),
                            contentKind = row.requireText("contentKind"),
                            discNumber = row.requireLong("discNumber").toInt(),
                        )
                        val prior = selected[gameId]
                        when {
                            prior == null -> {
                                budget.trackRetainedRow(*candidate.retainedTextFields())
                                selected[gameId] = candidate
                            }
                            candidate.preferredOver(prior) -> {
                                budget.replaceRetainedRow(
                                    prior.retainedTextFields(),
                                    candidate.retainedTextFields(),
                                )
                                selected[gameId] = candidate
                            }
                        }
                    }
                } else {
                    drainEntry(zip, shouldContinue)
                }
                zip.closeEntry()
            }
            if (zip.nextEntry != null) {
                throw RecoveryArchiveException("Recovery archive contains an unlisted restore payload.")
            }
        }
        return selected.mapValues { (_, row) ->
            val rawTitle = row.canonicalTitle ?: row.title
            val title = DisplayTitle.canonicalForGroup(rawTitle, row.duplicateGroupKind)
            RecoveryArchivedGameEvidence(
                normalizedGameId = row.gameId,
                platformId = row.platformId,
                fileName = row.displayName,
                sizeBytes = row.sizeBytes,
                relativePath = row.relativePath,
                contentHash = row.contentHash,
                serial = row.serial,
                title = title,
                platformLabel = platformLabels[row.platformId] ?: row.platformId,
            )
        }
    }

    private fun utf8ByteLength(value: String): Long =
        value.toByteArray(StandardCharsets.UTF_8).size.toLong()

    private fun readTable(
        zip: ZipInputStream,
        shouldContinue: () -> Boolean,
        consume: (JsonObject) -> Unit,
    ) {
        val line = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkRestoreContinue(shouldContinue)
            val read = zip.read(buffer)
            if (read < 0) break
            repeat(read) { index ->
                val value = buffer[index].toInt() and 0xff
                line.write(value)
                if (line.size() > RECOVERY_MAX_JSONL_LINE_BYTES) {
                    throw RecoveryArchiveException("Recovery restore row exceeds the supported line limit.")
                }
                if (value == '\n'.code) {
                    val bytes = line.toByteArray()
                    if (bytes.size == 1) {
                        throw RecoveryArchiveException("Recovery restore table contains an empty row.")
                    }
                    consume(parseRow(bytes, bytes.size - 1))
                    line.reset()
                }
            }
        }
        if (line.size() != 0) {
            throw RecoveryArchiveException("Recovery restore table row framing is invalid.")
        }
    }

    private fun parseRow(bytes: ByteArray, length: Int): JsonObject {
        val text = strictUtf8(bytes, length)
        validateCanonicalRecoveryJsonObject(text)
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (error: SerializationException) {
            throw RecoveryArchiveException("Recovery restore row is malformed.", error)
        } ?: throw RecoveryArchiveException("Recovery restore row is not an object.")
    }

    private fun requireEntry(zip: ZipInputStream, path: String) {
        val entry = zip.nextEntry
            ?: throw RecoveryArchiveException("Recovery archive is missing $path.")
        if (entry.name != path) {
            throw RecoveryArchiveException("Recovery archive restore order mismatch.")
        }
    }

    private fun drainEntry(
        zip: ZipInputStream,
        shouldContinue: () -> Boolean,
    ) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkRestoreContinue(shouldContinue)
            if (zip.read(buffer) < 0) break
        }
    }

    private fun JsonObject.requireText(name: String): String {
        val primitive = this[name]?.jsonPrimitive
            ?: throw RecoveryArchiveException("Recovery restore row omits $name.")
        if (!primitive.isString) {
            throw RecoveryArchiveException("Recovery restore row field $name is not text.")
        }
        return primitive.content
    }

    private fun JsonObject.optionalText(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) {
            throw RecoveryArchiveException("Recovery restore row uses a non-canonical explicit null.")
        }
        val primitive = element.jsonPrimitive
        if (!primitive.isString) {
            throw RecoveryArchiveException("Recovery restore row field $name is not text.")
        }
        return primitive.content
    }

    private fun JsonObject.requireLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw RecoveryArchiveException("Recovery restore row field $name is not an integer.")

    private fun JsonObject.requireBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.booleanOrNull
            ?: throw RecoveryArchiveException("Recovery restore row field $name is not a boolean.")

    private data class CandidateGameRow(
        val gameId: String,
        val entryKey: String,
        val sourceRootUri: String?,
        val platformId: String,
        val displayName: String,
        val title: String,
        val canonicalTitle: String?,
        val duplicateGroupKind: DuplicateGroupKind?,
        val sizeBytes: Long,
        val contentHash: String?,
        val serial: String?,
        val relativePath: String,
        val availability: String,
        val isPrimaryMember: Boolean,
        val contentKind: String,
        val discNumber: Int,
    ) {
        fun preferredOver(other: CandidateGameRow): Boolean {
            val leftRank = selectionRank(this)
            val rightRank = selectionRank(other)
            return when {
                leftRank != rightRank -> leftRank < rightRank
                displayName != other.displayName -> displayName < other.displayName
                sourceRootUri.orEmpty() != other.sourceRootUri.orEmpty() ->
                    sourceRootUri.orEmpty() < other.sourceRootUri.orEmpty()
                relativePath != other.relativePath -> relativePath < other.relativePath
                else -> entryKey < other.entryKey
            }
        }

        fun retainedTextFields(): Array<String> = arrayOf(
            gameId,
            entryKey,
            sourceRootUri.orEmpty(),
            platformId,
            displayName,
            title,
            canonicalTitle.orEmpty(),
            contentHash.orEmpty(),
            serial.orEmpty(),
            relativePath,
            availability,
            contentKind,
        )

        private fun selectionRank(row: CandidateGameRow): Int {
            val availabilityRank = if (row.availability == "AVAILABLE") 0 else 1
            val primaryRank = if (row.isPrimaryMember) 0 else 1
            val discRank = if (row.discNumber > 0) 0 else 1
            val contentKindRank = when (
                ContentKind.entries.firstOrNull { it.name == row.contentKind }
            ) {
                ContentKind.DISC_MEMBER -> 0
                ContentKind.GAME,
                ContentKind.MULTI_DISC_PLAYLIST,
                ContentKind.TRACK_MANIFEST,
                -> 1
                else -> 2
            }
            return availabilityRank * 1_000 +
                primaryRank * 100 +
                discRank * 10 +
                contentKindRank
        }
    }
}

private fun strictUtf8(bytes: ByteArray, length: Int): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (error: Exception) {
        throw RecoveryArchiveException("Recovery restore row contains invalid UTF-8.", error)
    }
}

private fun checkRestoreContinue(shouldContinue: () -> Boolean) {
    if (!shouldContinue()) throw RecoveryArchiveCancelledException()
}
