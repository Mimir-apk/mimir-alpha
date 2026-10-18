package com.gruning.mimir.rewrite.core.storage.mapper

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.GameIdentity
import com.gruning.mimir.rewrite.core.model.HiddenState
import com.gruning.mimir.rewrite.core.model.HideReason
import com.gruning.mimir.rewrite.core.model.IdentitySource
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentAvailability
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.ResidentLocationKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity

/**
 * Entity ⇄ core-model mappers. Enums persist by their stable `name` (compact + human-readable in the
 * DB); unknown/blank strings degrade gracefully to the safest default rather than throwing, so a
 * forward-compat DB row never crashes a read.
 *
 * `INTENT_SENT_MARKER` is the sentinel stored in `LaunchProofEntity.outcome` for a successful send;
 * any other value is parsed as a [LaunchDispatchFailure] name.
 */
private const val INTENT_SENT_MARKER = "intent_sent"

// ── ResidentFile ⇄ GameEntity ────────────────────────────────────────────────

fun GameEntity.toResidentFile(): ResidentFile = ResidentFile(
    entryKey = EntryKey(entryKey),
    platformId = PlatformId(platformId),
    contentKind = enumOrDefault(contentKind, ContentKind.UNKNOWN),
    displayName = displayName,
    location = enumOrDefault(locationKind, ResidentLocationKind.EXTERNAL_REFERENCE),
    availability = enumOrDefault(availability, ResidentAvailability.AVAILABLE),
    sizeBytes = sizeBytes,
    contentHash = contentHash,
    sourceRootUri = sourceRootUri,
    sourceDocumentUri = sourceDocumentUri,
    managedPath = managedPath,
    relativePath = relativePath,
    discNumber = discNumber,
    identity = GameIdentity(
        gameId = GameId(gameId),
        title = title,
        region = region,
        version = version,
        titleCatalogKey = titleCatalogKey,
        serial = serial,
        source = enumOrDefault(identitySource, IdentitySource.NONE),
    ),
)

/**
 * Build a persistence row from a [ResidentFile] plus its dedup-group facts. The group facts
 * (`duplicateGroupId`, `isPrimaryMember`, `groupKind`) are supplied separately because they belong to
 * the GROUP the resolver formed, not to the file in isolation.
 */
fun ResidentFile.toEntity(
    duplicateGroupId: String? = null,
    isPrimaryMember: Boolean = true,
    groupKind: DuplicateGroupKind? = null,
): GameEntity {
    val id = requireNotNull(identity) { "ResidentFile.identity required to persist (gameId group key)" }
    val exactSourceRootUri = requireNotNull(sourceRootUri) {
        "ResidentFile.sourceRootUri required to persist exact physical ownership"
    }
    val expectedEntryKey = physicalResidentKey(exactSourceRootUri, relativePath)
    require(entryKey.value == expectedEntryKey) {
        "ResidentFile.entryKey must match its exact physical ownership"
    }
    return GameEntity(
        entryKey = entryKey.value,
        gameId = id.gameId.value,
        platformId = platformId.value,
        contentKind = contentKind.name,
        displayName = displayName,
        title = id.title,
        region = id.region,
        version = id.version,
        serial = id.serial,
        titleCatalogKey = id.titleCatalogKey,
        identitySource = id.source.name,
        locationKind = location.name,
        availability = availability.name,
        sizeBytes = sizeBytes,
        contentHash = contentHash,
        sourceRootUri = exactSourceRootUri,
        sourceDocumentUri = sourceDocumentUri,
        managedPath = managedPath,
        relativePath = relativePath,
        discNumber = discNumber,
        duplicateGroupId = duplicateGroupId,
        isPrimaryMember = isPrimaryMember,
        duplicateGroupKind = groupKind?.name,
    )
}

// ── HiddenState ⇄ HiddenItemEntity ───────────────────────────────────────────

fun HiddenItemEntity.toHiddenState(): HiddenState = HiddenState(
    hidden = hidden,
    reason = reason?.let { enumOrNull<HideReason>(it) },
    duplicateGroupId = duplicateGroupId,
    isPrimaryMember = isPrimaryMember,
)

fun HiddenState.toEntity(gameId: GameId, hiddenAtMillis: Long): HiddenItemEntity = HiddenItemEntity(
    gameId = gameId.value,
    hidden = hidden,
    reason = reason?.name,
    duplicateGroupId = duplicateGroupId,
    isPrimaryMember = isPrimaryMember,
    hiddenAtMillis = hiddenAtMillis,
)

// ── BiosProfile ⇄ BiosProfileEntity ──────────────────────────────────────────

fun BiosProfileEntity.toBiosProfile(): BiosProfile = BiosProfile(
    platformId = PlatformId(platformId),
    fileName = fileName,
    required = required,
    presence = enumOrDefault(presence, BiosPresence.UNKNOWN),
    emulatorId = emulatorScopeKey.takeIf { it.isNotEmpty() }?.let(::EmulatorId),
    packageName = packageName,
    expectedHash = expectedHash,
    expectedRelativePath = expectedRelativePath,
)

fun BiosProfile.toEntity(): BiosProfileEntity = BiosProfileEntity(
    platformId = platformId.value,
    emulatorScopeKey = emulatorId?.value.orEmpty(),
    packageName = packageName,
    fileName = fileName,
    required = required,
    presence = presence.name,
    expectedHash = expectedHash,
    expectedRelativePath = expectedRelativePath,
)

// ── EmulatorOverride ⇄ EmulatorOverrideEntity ────────────────────────────────

/** The persisted override, or null when the stored value is an explicit Auto reset (empty string). */
fun EmulatorOverrideEntity.toEmulatorIdOrNull(): EmulatorId? =
    emulatorId.takeIf { it.isNotBlank() }?.let(::EmulatorId)

fun emulatorOverrideEntity(gameId: GameId, emulatorId: EmulatorId?, setAtMillis: Long) =
    EmulatorOverrideEntity(
        gameId = gameId.value,
        emulatorId = emulatorId?.value ?: "",
        setAtMillis = setAtMillis,
    )

// ── LaunchProof ⇄ LaunchProofEntity ──────────────────────────────────────────

fun LaunchProofEntity.toLaunchProof(): LaunchProof = LaunchProof(
    gameId = GameId(requireNotNull(gameId) { "Android-package proof cannot be read as an emulated proof" }),
    platformId = PlatformId(platformId),
    emulatorId = EmulatorId(emulatorId),
    locator = locator,
    outcome = if (outcome == INTENT_SENT_MARKER) {
        LaunchOutcome.IntentSent
    } else {
        LaunchOutcome.Failed(enumOrDefault(outcome, LaunchDispatchFailure.BRIDGE_ERROR), EmulatorId(emulatorId))
    },
    timestampMillis = timestampMillis,
)

fun LaunchProof.toEntity(): LaunchProofEntity = LaunchProofEntity(
    gameId = gameId.value,
    androidPackageName = null,
    platformId = platformId.value,
    emulatorId = emulatorId.value,
    locator = locator,
    outcome = outcome.toStoredMarker(),
    timestampMillis = timestampMillis,
)

/** Android-game proofs use an explicit package target and never manufacture a ROM GameId. */
fun AndroidGameLaunchProof.toEntity(): LaunchProofEntity = LaunchProofEntity(
    gameId = null,
    androidPackageName = packageName.value,
    platformId = "android-games",
    emulatorId = packageName.value,
    locator = "package:${packageName.value}",
    outcome = outcome.toStoredMarker(),
    timestampMillis = timestampMillis,
)

private fun LaunchOutcome.toStoredMarker(): String = when (this) {
    is LaunchOutcome.IntentSent -> INTENT_SENT_MARKER
    is LaunchOutcome.Failed -> reason.name
}

// ── enum parsing helpers (forward-compatible, never throw on unknown) ─────────

private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    enumValues<E>().firstOrNull { it.name == name }

private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumOrNull<E>(name) ?: default
