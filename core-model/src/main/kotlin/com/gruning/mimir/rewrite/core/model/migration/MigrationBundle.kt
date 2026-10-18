package com.gruning.mimir.rewrite.core.model.migration

import kotlinx.serialization.Required
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * MIGRATION BUNDLE v1 — the portable, cross-app "bring over from Mimir" contract.
 *
 * The rewrite is side-by-side and CANNOT read the shipping app's private sandbox (the shipping app's data must remain untouched). So user state moves through
 * ONE plain-JSON file the shipping app writes into SHARED storage (`…/Mimir/…`), which the rewrite reads
 * via SAF. Metadata only — favorites/hidden/overrides/theme markers, never ROM bytes, BIOS, firmware, or
 * anything network-touching. The file is inspectable by design (no encryption) — same honest posture as
 * the rest of Mimir.
 *
 * **This is a WIRE CONTRACT.** Fields are JS-friendly primitives (platformId/emulatorId are raw strings,
 * not the internal value classes) so the shipping WebView app can emit it with plain `JSON.stringify`.
 * The importer converts to typed ids. Forward-compatible: unknown fields are ignored on read, every record
 * array is optional/defaulted, and additive changes stay within [CURRENT_BUNDLE_VERSION]; a breaking change
 * bumps the version and the reader rejects it (see [MigrationBundleCodec]).
 */
@kotlinx.serialization.Serializable
data class MigrationBundleV1(
    // @Required so an absent version/kind is a HARD parse failure (→ friendly "not a migration bundle"),
    // not silently defaulted to a valid-looking empty bundle. The Json config sets encodeDefaults=true so
    // our own encode still emits them.
    @Required val bundleVersion: Int = CURRENT_BUNDLE_VERSION,
    @Required val kind: String = BUNDLE_KIND,
    val producedByApp: String = "",
    val producedByVersion: String = "",
    val producedAtMillis: Long = 0L,
    val profile: MigrationProfile? = null,
    val theme: MigrationTheme? = null,
    val sourceRoots: List<MigrationSourceRoot> = emptyList(),
    val favorites: List<MigrationGameRecord> = emptyList(),
    val hidden: List<MigrationGameRecord> = emptyList(),
    val emulatorOverrides: List<MigrationEmulatorOverride> = emptyList(),
    /**
     * RetroAchievements progress, so a restore or a move to another device does not start from zeros. ADDITIVE within [CURRENT_BUNDLE_VERSION]: a bundle written
     * before it has none, and an older reader ignores it. Counts only; never a Web API key or any credential.
     */
    val achievements: List<MigrationAchievementAccount> = emptyList(),
)

/** Stable local profile key (always "local") + optional display name (rewrite v5 may drop the name). */
@kotlinx.serialization.Serializable
data class MigrationProfile(
    val localProfileId: String = "local",
    val displayName: String? = null,
)

/** The active theme, as a [com.gruning.mimir.rewrite.core.ui] `MimirThemeId.storageKey` string. */
@kotlinx.serialization.Serializable
data class MigrationTheme(val themeId: String)

/**
 * A library source folder — HINT ONLY. OS-scoped SAF `content://` tree grants are non-transferable across
 * packages, so the rewrite records these for the user to RE-PICK, never auto-grants them.
 */
@kotlinx.serialization.Serializable
data class MigrationSourceRoot(
    val friendlyLabel: String,
    val treeUriHint: String? = null,
    val isPrimary: Boolean = false,
)

/**
 * The join-key-bearing unit. Carries the strongest identity EVIDENCE the shipping app has per game so the
 * importer can re-derive the rewrite's `gameId` through the one `GameIdentityBuilder` (hash → serial →
 * catalog → filename). The human-readable [title]/[platformLabel] are for the "not found" report ONLY —
 * they are never matched on.
 *
 * @property platformId the rewrite platform key (e.g. "dreamcast").
 * @property fileName original file name including extension.
 * @property sizeBytes byte size if known (0 if unknown) — part of the stable key.
 * @property relativePath path relative to the library root, if known.
 * @property contentHash a byte-exact ROM identity hash, ONLY if the producer actually has one (usually null;
 *   the shipping app must NOT emit an inbox-dedup-scoped hash here — that would mis-match).
 * @property serial an in-image serial/title-id, if known (usually null).
 */
@kotlinx.serialization.Serializable
data class MigrationGameRecord(
    val platformId: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val relativePath: String? = null,
    val contentHash: String? = null,
    val serial: String? = null,
    val title: String = "",
    val platformLabel: String = "",
)

/**
 * One RetroAchievements account's saved progress: the user's name on RetroAchievements (a public identifier; it is what ties these rows to the account that signs in
 * on the other device, so a restore can never put one account's counts under another), their total points when known, and the games they have progress on.
 */
@kotlinx.serialization.Serializable
data class MigrationAchievementAccount(
    val username: String,
    /** The profile's hardcore score (RetroAchievements' main one). */
    val points: Int? = null,
    val games: List<MigrationAchievementGame> = emptyList(),
    /** The profile's softcore score, kept apart from [points] and never added to it; absent in a backup written before it was carried. Last, so a positional caller is unchanged. */
    val softcorePoints: Int? = null,
)

/** One game of that progress, keyed by RetroAchievements' own game id (the same on every device), so no library identity is needed to carry it. */
@kotlinx.serialization.Serializable
data class MigrationAchievementGame(
    val raGameId: Int,
    val title: String,
    val consoleName: String = "",
    val earned: Int,
    val total: Int,
    val lastAwardedAtMillis: Long? = null,
    /** A relative `/Images/<digits>.png` path or null; checked again on the way in. */
    val iconPath: String? = null,
    /** How many of [earned] were earned in hardcore (inside [earned], never added to it); 0 in a backup written before it was carried. */
    val earnedHardcore: Int = 0,
    /**
     * RetroAchievements' own word for the highest award on the game ("beaten-softcore", "beaten-hardcore", "completed", "mastered"), or null for none. A string because this
     * module does not know the metadata types; a word the restoring app does not know is read as no award, never guessed at.
     */
    val awardWord: String? = null,
)

/** A per-game emulator override: the game + the chosen emulator's stable id (a launcher/package key). */
@kotlinx.serialization.Serializable
data class MigrationEmulatorOverride(
    val game: MigrationGameRecord,
    val emulatorId: String,
)

const val CURRENT_BUNDLE_VERSION: Int = 1
const val BUNDLE_KIND: String = "mimir.migration.bundle"

/** A typed failure the UI can render as a friendly message instead of crashing on a bad file. */
class MigrationBundleException(message: String) : Exception(message)

/**
 * Parse + validate a migration bundle. Tolerant of unknown/extra fields (forward-compat), strict on the
 * two things that guarantee we're reading the right kind of file at a version we understand.
 */
object MigrationBundleCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true // so encode always writes the @Required version/kind (self-describing)
    }

    /** @return the parsed bundle, or a [MigrationBundleException] failure (never a raw crash). */
    fun parse(text: String): Result<MigrationBundleV1> = runCatching {
        val bundle = try {
            json.decodeFromString(MigrationBundleV1.serializer(), text)
        } catch (e: SerializationException) {
            // Malformed JSON OR a missing @Required field (version/kind) — either way it isn't our bundle.
            throw MigrationBundleException("This file isn't a Mimir migration bundle. Pick the file the other Mimir saved.")
        }
        if (bundle.kind != BUNDLE_KIND) {
            throw MigrationBundleException(
                "This file isn't a Mimir migration bundle (kind was \"${bundle.kind}\").",
            )
        }
        if (bundle.bundleVersion > CURRENT_BUNDLE_VERSION) {
            throw MigrationBundleException(
                "This bundle is version ${bundle.bundleVersion}; this app understands up to " +
                    "$CURRENT_BUNDLE_VERSION. Update Mimir and try again.",
            )
        }
        bundle
    }

    /** Encode a bundle (used by tests + any future in-app export). */
    fun encode(bundle: MigrationBundleV1): String =
        json.encodeToString(MigrationBundleV1.serializer(), bundle)
}
