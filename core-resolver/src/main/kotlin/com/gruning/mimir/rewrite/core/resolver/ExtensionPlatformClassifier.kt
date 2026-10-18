package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistry
import com.gruning.mimir.rewrite.core.platform.PlatformRegistryLoader

/**
 * Discovery-only extension and folder classifier backed by the locked platform registry.
 *
 * Intrinsic extensions classify when the registry gives one candidate. Generic executables/data/packages
 * and multi-platform containers need compatible exact folder evidence. This deliberately does not consult
 * routes, activation, or certification data: a discovered file is not a launch claim.
 */
sealed interface PlatformResolution {
    data class Resolved(val platformId: PlatformId) : PlatformResolution
    data object NoCandidates : PlatformResolution
    data object Ambiguous : PlatformResolution
    data object FolderExtensionConflict : PlatformResolution
    data object ContextRequired : PlatformResolution
}

class ExtensionPlatformClassifier(
    private val registry: PlatformRegistry = lockedRegistry,
) : PlatformClassifier {

    /** Legacy entry point delegates to the typed policy; generic unique extensions cannot escape it. */
    override fun classify(input: ResolutionInput): PlatformId? =
        (resolveWithContext(input) as? PlatformResolution.Resolved)?.platformId

    /**
     * Resolves a unique extension directly, or an ambiguous extension when [platformHint] is one
     * of its registry candidates. No extension is assigned a handwritten fallback platform.
     */
    fun classifyWithContext(input: ResolutionInput, platformHint: PlatformId? = null): PlatformId? {
        return (resolveWithContext(input, platformHint) as? PlatformResolution.Resolved)?.platformId
    }

    /** Resolve with evidence precedence and retain the precise fail-closed reason. */
    fun resolveWithContext(input: ResolutionInput, platformHint: PlatformId? = null): PlatformResolution {
        val candidates = candidatesFor(input.extension)
        if (candidates.isEmpty()) return PlatformResolution.NoCandidates

        if (platformHint != null) {
            val canonicalHint = canonicalId(platformHint)
            return if (canonicalHint in candidates) {
                PlatformResolution.Resolved(legacyCompatible(canonicalHint))
            } else {
                PlatformResolution.FolderExtensionConflict
            }
        }

        if (candidates.size > 1) return PlatformResolution.Ambiguous
        if (input.extension.lowercase() in CONTEXT_REQUIRED_EXTENSIONS) return PlatformResolution.ContextRequired
        return PlatformResolution.Resolved(legacyCompatible(candidates.single()))
    }

    /**
     * Resolves an ambiguous extension only through an exact folder-derived platform hint.
     *
     * The nullable result is intentional: without enough discovery evidence a container remains
     * ambiguous rather than being guessed into a platform.
     */
    fun resolveContainer(extension: String, hint: PlatformId?): PlatformId? {
        val input = ResolutionInput(fileName = "file.$extension", extension = extension.lowercase())
        return (resolveWithContext(input, hint) as? PlatformResolution.Resolved)?.platformId
    }

    /**
     * The canonical folder name for [platformId] — `pc_games` -> `pcgames`. Null when unknown.
     *
     * A recognised `.steam` shortcut may lack a destination when its platform folder does not yet exist. To
     * offer the folder that SHOULD exist, something has to know its name, and the registry is the only
     * thing that does — the platform id is not the folder name and guessing the mapping is how a "pc_games"
     * folder gets created beside the "pcgames" one the scanner actually looks for.
     */
    fun folderNameFor(platformId: String): String? =
        registry.definition(PlatformId(platformId))?.folder?.canonical?.normalizedSegment

    /** True when the locked registry associates [extension] with more than one platform. */
    fun isAmbiguousContainer(extension: String): Boolean = candidatesFor(extension).size > 1

    private fun candidatesFor(extension: String): List<PlatformId> =
        registry.extensionCandidates(extension).distinct()

    private fun canonicalId(id: PlatformId): PlatformId =
        registry.canonicalize(IdentityNamespace.REWRITE_LEGACY, id.value) ?: id

    /** Keep resolver consumers on their established short ids while the registry uses canonical ids. */
    private fun legacyCompatible(id: PlatformId): PlatformId =
        registry.definition(id)
            ?.aliases
            ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
            ?.normalizedValue
            ?.let(::PlatformId)
            ?: id

    companion object {
        private const val LOCKED_REGISTRY_RESOURCE = "mimir-platform-registry.v2.json"

        val CONTEXT_REQUIRED_EXTENSIONS = setOf(
            "dat", "lst", "exe", "o", "dump", "gz", "pkg", "self", "vpk", "wad", "uae", "rpx",
            "xex", "xbla", "zar", "prx", "nro", "nso",
        )

        /** One parse per process, shared by the scanner and by `PlatformLabels` for the names its table lacks. */
        internal val lockedRegistry: PlatformRegistry by lazy {
            val input = requireNotNull(
                ExtensionPlatformClassifier::class.java.classLoader
                    ?.getResourceAsStream(LOCKED_REGISTRY_RESOURCE),
            ) {
                "Missing locked platform registry resource: $LOCKED_REGISTRY_RESOURCE"
            }
            input.use { PlatformRegistryLoader.load(it).requireRegistry() }
        }

        /**
         * Derives a platform hint from path segments using the registry's exact normalized folder
         * index. Ambiguous aliases (for example a vendor-only folder) intentionally yield no hint.
         */
        fun platformHintFromFolder(
            pathOrFolder: String?,
            registry: PlatformRegistry = lockedRegistry,
        ): PlatformId? {
            if (pathOrFolder.isNullOrBlank()) return null
            val segments = pathOrFolder.split('/', '\\').filter { it.isNotBlank() }
            for (segment in segments.asReversed()) {
                val candidate = registry.folderCandidates(segment).distinct().singleOrNull() ?: continue
                return legacyCompatible(registry, candidate)
            }
            return null
        }

        private fun legacyCompatible(registry: PlatformRegistry, id: PlatformId): PlatformId =
            registry.definition(id)
                ?.aliases
                ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
                ?.normalizedValue
                ?.let(::PlatformId)
                ?: id
    }
}
