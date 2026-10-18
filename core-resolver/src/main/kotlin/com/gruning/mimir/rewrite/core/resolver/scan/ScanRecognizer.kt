package com.gruning.mimir.rewrite.core.resolver.scan

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.ResidentFile
import com.gruning.mimir.rewrite.core.model.ResidentLocationKind
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.GameIdentityBuilder
import com.gruning.mimir.rewrite.core.resolver.GameNameResolver
import com.gruning.mimir.rewrite.core.resolver.LocalContentRole
import com.gruning.mimir.rewrite.core.resolver.PlatformResolution
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput

/** A [WalkedFile] recognized into a [ResidentFile], or a skip reason when it is not a game. */
sealed interface RecognitionResult {
    data class Recognized(val file: ResidentFile) : RecognitionResult
    data class Skipped(val reason: SkipReason) : RecognitionResult
}

/** Why a walked file was not turned into a resident game. */
enum class SkipReason {
    /** Extension maps to no known platform (art, saves, text, BIOS, …). */
    UNKNOWN_EXTENSION,

    /** Files under hidden bookkeeping folders such as `.mimir/` are never playable content. */
    HIDDEN_PATH,

    /** Known metadata/checksum sidecars are rejected before platform classification. */
    SIDECAR_EXTENSION,

    /** A support/BIOS/update/DLC path is not a playable base game. */
    SUPPORT_CONTENT,

    /** Empty files cannot be games. */
    EMPTY_FILE,

    /** Shipping's format-specific floor proves the payload is implausibly small. */
    IMPLAUSIBLE_SIZE,

    /** Exact folder platform evidence contradicts the extension registry. */
    FOLDER_EXTENSION_CONFLICT,

    /** The extension belongs to several platforms and no exact folder resolves it. */
    AMBIGUOUS_PLATFORM,

    /** A generic host/data/executable extension requires exact platform folder context. */
    PLATFORM_CONTEXT_REQUIRED,
}

/**
 * Turns raw [WalkedFile]s into resolved [ResidentFile]s: extension (+ folder context) → platform, then
 * evidence → identity, then content-kind / disc-number from the filename. This is the per-file half of
 * the scan; grouping (dedup) is a separate batch stage over the results.
 *
 * Read-in-place: the resident carries the exact SAF document URI OR the plain path from the walk; the
 * scan never moves the file.
 */
class ScanRecognizer(
    private val classifier: ExtensionPlatformClassifier = ExtensionPlatformClassifier(),
    private val identityBuilder: GameIdentityBuilder = GameIdentityBuilder(),
    private val nameResolver: GameNameResolver = GameNameResolver(),
) {

    /**
     * Recognize one walked file under a source root.
     *
     * @param file the walked file facts.
     * @param rootUri the scanned source root (for the SAF tree root URI + cross-root key stability).
     * @param folderContext a path/folder string used to derive the platform hint for ambiguous
     *   containers; defaults to the file's relative path under the root.
     */
    fun recognize(
        file: WalkedFile,
        rootUri: String,
        folderContext: String = file.relativePath,
    ): RecognitionResult {
        val ext = file.displayName.substringAfterLast('.', "").lowercase()
        if (file.displayName.startsWith('.') || hasHiddenParent(file.relativePath)) {
            return RecognitionResult.Skipped(SkipReason.HIDDEN_PATH)
        }
        if (ext in SIDECAR_EXTENSIONS) {
            return RecognitionResult.Skipped(SkipReason.SIDECAR_EXTENSION)
        }

        val input = ResolutionInput(
            fileName = file.displayName,
            extension = ext,
            sizeBytes = file.sizeBytes,
        )
        val pathOnlyName = nameResolver.resolve(input, relativePath = file.relativePath)
        if (pathOnlyName.contentRole != LocalContentRole.BASE) {
            return RecognitionResult.Skipped(SkipReason.SUPPORT_CONTENT)
        }
        if (file.sizeBytes <= 0L) return RecognitionResult.Skipped(SkipReason.EMPTY_FILE)
        if (file.sizeBytes < minimumPlausibleSize(ext)) {
            return RecognitionResult.Skipped(SkipReason.IMPLAUSIBLE_SIZE)
        }

        val hint = ExtensionPlatformClassifier.platformHintFromFolder(folderContext)
        val platform: PlatformId = when (val resolution = classifier.resolveWithContext(input, hint)) {
            is PlatformResolution.Resolved -> resolution.platformId
            PlatformResolution.NoCandidates -> return RecognitionResult.Skipped(SkipReason.UNKNOWN_EXTENSION)
            PlatformResolution.Ambiguous -> return RecognitionResult.Skipped(SkipReason.AMBIGUOUS_PLATFORM)
            PlatformResolution.FolderExtensionConflict ->
                return RecognitionResult.Skipped(SkipReason.FOLDER_EXTENSION_CONFLICT)
            PlatformResolution.ContextRequired ->
                return RecognitionResult.Skipped(SkipReason.PLATFORM_CONTEXT_REQUIRED)
        }

        val resolvedName = nameResolver.resolve(input, platform, file.relativePath)
        if (resolvedName.contentRole != LocalContentRole.BASE) {
            return RecognitionResult.Skipped(SkipReason.SUPPORT_CONTENT)
        }
        val identity = identityBuilder.build(platform, input, resolvedName)
        val disc = discNumberOf(file.displayName)
        val contentKind = when {
            ext == "m3u" -> ContentKind.MULTI_DISC_PLAYLIST
            ext in TRACK_MANIFESTS -> ContentKind.TRACK_MANIFEST
            disc > 0 -> ContentKind.DISC_MEMBER
            else -> ContentKind.GAME
        }

        val location = if (file.filePath != null) {
            ResidentLocationKind.MANAGED_PATH
        } else {
            ResidentLocationKind.SAF_TREE_GRANT
        }

        val resident = ResidentFile(
            entryKey = EntryKey(stableEntryKey(rootUri, file)),
            platformId = platform,
            contentKind = contentKind,
            displayName = file.displayName,
            location = location,
            sizeBytes = file.sizeBytes,
            contentHash = null, // hashing is a later, costlier stage; filename-only identity for now
            // Root ownership is required for exact detach/return reconciliation on both SAF trees and
            // managed removable paths. The per-file launch locator remains sourceDocumentUri/managedPath.
            sourceRootUri = rootUri,
            sourceDocumentUri = file.documentUri,
            managedPath = file.filePath,
            relativePath = file.relativePath,
            discNumber = disc,
            identity = identity,
        )
        return RecognitionResult.Recognized(resident)
    }

    /** A collision-free, canonical per-file key over exact root ownership and relative path. */
    private fun stableEntryKey(rootUri: String, file: WalkedFile): String =
        physicalResidentKey(rootUri, file.relativePath)

    private fun discNumberOf(name: String): Int {
        val match = DISC_TOKEN.find(name) ?: return 0
        val token = match.groupValues.getOrNull(2).orEmpty().ifEmpty {
            match.groupValues.getOrNull(3).orEmpty()
        }
        return discOrdinal(token)
    }

    private fun discOrdinal(token: String): Int = token.toIntOrNull() ?: when (token.uppercase()) {
        "A" -> 1
        "B" -> 2
        "I" -> 1
        "II" -> 2
        "III" -> 3
        "IV" -> 4
        "V" -> 5
        "VI" -> 6
        "VII" -> 7
        "VIII" -> 8
        "IX" -> 9
        "X" -> 10
        else -> 0
    }

    private fun hasHiddenParent(relativePath: String): Boolean =
        relativePath
            .split('/', '\\')
            .filter { it.isNotBlank() }
            .dropLast(1)
            .any { it.startsWith('.') && it != "." && it != ".." }

    private fun minimumPlausibleSize(extension: String): Long = when (extension) {
        in FLOOR_32_KIB -> 32L * 1024
        in FLOOR_128_KIB -> 128L * 1024
        in FLOOR_1_MIB -> 1024L * 1024
        else -> 1L
    }

    private companion object {
        // ".cue"/".ccd"/".gdi" are track manifests for a disc split into .bin/.img tracks. They are
        // INDEXED as TRACK_MANIFEST (not skipped) and become the launch member of their track set; the
        // grouper folds the sibling tracks behind them. CloneCD's `.ccd` is the launchable descriptor;
        // its `.sub` remains a sidecar.
        val TRACK_MANIFESTS = setOf("cue", "ccd", "gdi")
        // Roman ordinals require a separator so the Dreamcast `.cdi` extension is never parsed as "CD I".
        val DISC_TOKEN = Regex(
            """\(?\s*(disc|disk|cd|side)(?:\s*[-_]?\s*([0-9]+|[ab])|[\s_-]+(viii|vii|vi|iv|iii|ii|ix|x|v|i)\b)""",
            RegexOption.IGNORE_CASE,
        )
        val SIDECAR_EXTENSIONS = setOf(
            "json", "xml", "txt", "text", "nfo", "md5", "sha1", "sfv", "rtf", "pdf", "doc", "docx",
            "log", "csv", "tsv", "sbi", "sub", "idx", "mds", "toc",
        )
        val FLOOR_32_KIB = setOf("gb", "dmg", "sgb", "gbx", "gbc", "cgb")
        val FLOOR_128_KIB = setOf("gba", "agb", "nds", "dsi", "ids")
        val FLOOR_1_MIB = setOf(
            "3ds", "cia", "cci", "cxi", "cfa", "iso", "gcm", "rvz", "wbfs", "cso", "xci", "nsp",
            "xcz", "nsz",
        )
    }
}
