package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.LocalListingPolicy
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.PlatformResolution
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput
import com.gruning.mimir.rewrite.feature.files.FileBrowserRecognition
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Threads the resolver's REAL platform classifier into the file browser.
 *
 * `FileBrowserController.classifyPlatform` shipped as `{ null }` (adoption spec §9: *"Platform
 * recognition is unthreaded"*), so no tome or file ever drew a platform glyph and the inspector's
 * "Launches" row could never name an emulator. This is the adapter that closes that.
 *
 * **Injected, never duplicated.** [ExtensionPlatformClassifier] is the resolver's own registry-backed
 * classifier -- the same one `ScanRecognizer` uses -- and it lives in `core-resolver`, which
 * `feature-files` cannot depend on and `core-files` (vendored, deliberately dependency-light) must not.
 * `app/` already depends on both, which is why the adapter belongs here. A second extension table in the
 * browser would drift from the scanner's and the drift would be invisible -- the same reasoning
 * `FolderBrowserEnumeration.kt:39` already recorded for its own injected classifier.
 *
 * **Offline.** The registry is a bundled resource (`mimir-platform-registry.v2.json`, loaded lazily from
 * the classpath) and the classifier consults nothing else. No network on any path here.
 *
 * **Fail-closed is the resolver's ruling, not a gap here.** An ambiguous extension with no usable folder
 * evidence, or a folder hint that contradicts the extension, resolves to null and the row simply draws
 * no glyph -- `MimirPlatformShelfmark` renders nothing rather than a meaningless plate
 * (`MimirPlatformShelfmark.kt:87-89`). A `.gba` file sitting in a folder named `psx` is therefore
 * unlabelled rather than mislabelled, which is the correct trade for an identity marker.
 */
internal object FileBrowserPlatformClassifier {

    private val classifier = ExtensionPlatformClassifier()
    private val parentFolderHints = ParentFolderHintCache(
        lookup = { folder -> ExtensionPlatformClassifier.platformHintFromFolder(folder) },
    )

    /**
     * The `(FileItem) -> FileBrowserRecognition` `FileBrowserController.classifyPlatform` expects: the
     * platform when one is settled, whether the row is a game the resolver could not place, and whether it is
     * an emulator BIOS. See [fileBrowserRecognition].
     */
    val forBrowser: (FileItem) -> FileBrowserRecognition = { item ->
        fileBrowserRecognition(
            item = item,
            folderHint = { folder -> ExtensionPlatformClassifier.platformHintFromFolder(folder) },
            classifyFile = { input, hint -> classifier.classifyWithContext(input, hint) },
            resolveFile = { input, hint -> classifier.resolveWithContext(input, hint) },
            parentHintCache = parentFolderHints,
            isBios = FileBrowserRomFolders::isBiosPayload,
        )
    }
}

/**
 * A small per-process cache for the one path-derived question the file browser asks repeatedly.
 * Negative answers are entries too: a large mixed folder must not make the resolver walk the same
 * path once per sibling. The registry is immutable, so synchronizing this bounded LRU is sufficient
 * for callers that render rows from more than one browser collector.
 */
internal class ParentFolderHintCache(
    private val lookup: (String) -> PlatformId?,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private data class Entry(val value: PlatformId?)

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(folder: String): PlatformId? {
        entries[folder]?.let { return it.value }

        val value = lookup(folder)
        if (entries.size >= maxEntries) {
            entries.entries.iterator().next().let { entries.remove(it.key) }
        }
        entries[folder] = Entry(value)
        return value
    }

    @get:Synchronized
    val size: Int
        get() = entries.size

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

/**
 * The pure decision behind [FileBrowserPlatformClassifier.forBrowser], with both resolver entry points
 * injected so it can be tested without loading the platform registry.
 *
 * **A directory is classified by its OWN name, not by its path.**
 * [ExtensionPlatformClassifier.platformHintFromFolder] walks path segments in reverse and returns the
 * first that resolves, so handing it a full path would let `/Roms/gba/Europe` inherit GBA from its
 * parent and draw a platform glyph on a folder that is not a platform. A tome wears a platform's mark
 * only when its own name claims one.
 *
 * **A file, by contrast, WANTS its parent as context.** That is precisely what unlocks the ambiguous
 * containers -- `.iso`, `.cso`, `.bin` map to several platforms, and the registry resolves them only
 * when exact folder evidence agrees ([ExtensionPlatformClassifier.resolveWithContext]). Passing the
 * parent directory is the difference between a recognised disc image and a blank tile.
 *
 * @param folderHint the registry's exact folder→platform index.
 * @param classifyFile extension + optional folder hint → platform.
 * @param resolveFile optional typed resolver used to distinguish a meaningful packaged inner-format
 *   conflict from an unknown dotted title before falling back to the outer archive behavior.
 * @param parentHintCache optional bounded cache for parent-folder lookups; directories deliberately
 *   bypass it because their own name is the only directory evidence.
 * @return the platform's id value, in the `REWRITE_LEGACY` short-id vocabulary the curated shelfmark
 *   table keys on (`MimirPlatformShelfmark.kt:232-243`: `"snes"`, `"n64"`, `"gba"`), or null.
 */
internal fun fileBrowserPlatformId(
    item: FileItem,
    folderHint: (String) -> PlatformId?,
    classifyFile: (ResolutionInput, PlatformId?) -> PlatformId?,
    resolveFile: ((ResolutionInput, PlatformId?) -> PlatformResolution)? = null,
    parentHintCache: ParentFolderHintCache? = null,
): String? = if (item.isDirectory) {
    folderHint(item.name)?.value
} else {
    fileBrowserFileResolution(item, folderHint, classifyFile, resolveFile, parentHintCache)
        .resolvedPlatform()?.value
}

/**
 * What the file browser knows about [item], beyond the platform id [fileBrowserPlatformId] answers.
 *
 * [fileBrowserPlatformId] threw away every
 * resolution except `Resolved`, so a `.iso` the registry knows is a game but cannot place on one platform
 * (several candidates, a folder name that contradicts the extension, or a container that needs folder
 * evidence) looked the same as a text file. It is still a game, and the card now says so
 * ([FileBrowserRecognition.gameLike]). The three unsettled outcomes -- `Ambiguous`, `FolderExtensionConflict`
 * and `ContextRequired` -- are exactly the ones where the extension IS game content; `NoCandidates` is the
 * only answer that means the registry has never heard of it.
 *
 * A BIOS payload ([isBios], injected: the resolver owns that knowledge) is reported whether or not a
 * platform also resolved, because `gba_bios.bin` in a `gba` folder resolves to GBA and the card must not call
 * it a game. Directories never carry `gameLike` or `bios`.
 */
internal fun fileBrowserRecognition(
    item: FileItem,
    folderHint: (String) -> PlatformId?,
    classifyFile: (ResolutionInput, PlatformId?) -> PlatformId?,
    resolveFile: ((ResolutionInput, PlatformId?) -> PlatformResolution)? = null,
    parentHintCache: ParentFolderHintCache? = null,
    isBios: (FileItem) -> Boolean = { false },
): FileBrowserRecognition {
    if (item.isDirectory) {
        return FileBrowserRecognition(platformIdValue = folderHint(item.name)?.value)
    }
    val resolution = fileBrowserFileResolution(item, folderHint, classifyFile, resolveFile, parentHintCache)
    val platform = resolution.resolvedPlatform()?.value
    return FileBrowserRecognition(
        platformIdValue = platform,
        gameLike = platform == null && resolution.isUnsettledGame(),
        bios = isBios(item),
    )
}

private fun fileBrowserFileResolution(
    item: FileItem,
    folderHint: (String) -> PlatformId?,
    classifyFile: (ResolutionInput, PlatformId?) -> PlatformId?,
    resolveFile: ((ResolutionInput, PlatformId?) -> PlatformResolution)?,
    parentHintCache: ParentFolderHintCache?,
): PlatformResolution {
    val parent = item.path.substringBeforeLast('/', "")
    val hint = if (parentHintCache != null) parentHintCache.get(parent) else folderHint(parent)
    val outerInput = ResolutionInput(
        fileName = item.name,
        extension = item.extension.lowercase(Locale.ROOT),
    )
    val typedResolver = resolveFile ?: { input: ResolutionInput, context: PlatformId? ->
        classifyFile(input, context)?.let(PlatformResolution::Resolved) ?: PlatformResolution.NoCandidates
    }

    return classifyPackagedFile(
        item = item,
        outerInput = outerInput,
        parentHint = hint,
        resolve = typedResolver,
    )
}

private fun classifyPackagedFile(
    item: FileItem,
    outerInput: ResolutionInput,
    parentHint: PlatformId?,
    resolve: (ResolutionInput, PlatformId?) -> PlatformResolution,
): PlatformResolution {
    return when (val packaged = packagedInnerFile(item.name, outerInput.extension)) {
        PackagedInnerFile.Plain -> resolve(outerInput, parentHint)
        PackagedInnerFile.Malformed -> PlatformResolution.NoCandidates
        is PackagedInnerFile.Inner -> {
            val innerResolution = resolve(packaged.input, parentHint)
            when (innerResolution) {
                // A dotted title or version token before `.zip`/`.7z` is not an inner ROM format.
                // Keep the old outer archive behavior in that case.
                PlatformResolution.NoCandidates -> resolve(outerInput, parentHint)
                else -> innerResolution
            }
        }
    }
}

private fun PlatformResolution.resolvedPlatform(): PlatformId? =
    (this as? PlatformResolution.Resolved)?.platformId

/** The extension is game content, but the registry could not settle which platform it belongs to. */
private fun PlatformResolution.isUnsettledGame(): Boolean =
    this == PlatformResolution.Ambiguous ||
        this == PlatformResolution.FolderExtensionConflict ||
        this == PlatformResolution.ContextRequired

private sealed interface PackagedInnerFile {
    data object Plain : PackagedInnerFile
    data object Malformed : PackagedInnerFile
    data class Inner(val input: ResolutionInput) : PackagedInnerFile
}

/**
 * Reads only the filename suffixes. At most [MAX_WRAPPER_DEPTH] supported archive suffixes may wrap
 * one explicit inner extension. No archive is opened and the filename is never treated as payload
 * validation; the result only feeds browser display and destination suggestions.
 */
private fun packagedInnerFile(name: String, outerExtension: String): PackagedInnerFile {
    if (outerExtension !in LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS) {
        return PackagedInnerFile.Plain
    }
    if (name.isBlank()) return PackagedInnerFile.Malformed

    val components = name.split('.')
    if (components.lastOrNull()?.lowercase(Locale.ROOT) != outerExtension) {
        // FileItem permits a caller-supplied extension. Preserve its legacy direct-file behavior when
        // that extension does not describe the visible name's suffix.
        return PackagedInnerFile.Plain
    }

    var index = components.lastIndex
    var wrapperDepth = 0
    while (index > 0 && components[index].lowercase(Locale.ROOT) in SUPPORTED_WRAPPER_EXTENSIONS) {
        wrapperDepth++
        if (wrapperDepth > MAX_WRAPPER_DEPTH) return PackagedInnerFile.Malformed
        index--
    }

    if (wrapperDepth == 0) return PackagedInnerFile.Plain
    if (index == 0) {
        // `Game.zip` has no explicit inner format and must keep the existing archive behavior. A
        // suffix-only name such as `.zip` has no stem, so it cannot be used as a ROM marker.
        return if (components[0].isNotBlank()) PackagedInnerFile.Plain else PackagedInnerFile.Malformed
    }

    // Dots belong in titles (`Dr...Mario.nds.zip`) and a leading dot may be part of a hidden title
    // (`.Game.nds.zip`). Only an actually blank joined stem is malformed.
    if (components.take(index).joinToString(".").isBlank()) return PackagedInnerFile.Malformed

    val innerExtension = components[index].lowercase(Locale.ROOT)
    if (innerExtension.isEmpty()) return PackagedInnerFile.Malformed

    val innerFileName = components.take(index + 1).joinToString(".")
    return PackagedInnerFile.Inner(
        ResolutionInput(fileName = innerFileName, extension = innerExtension),
    )
}

private val SUPPORTED_WRAPPER_EXTENSIONS: Set<String>
    get() = LocalListingPolicy.EXTRACTABLE_ARCHIVE_EXTENSIONS

private const val MAX_WRAPPER_DEPTH = 3
