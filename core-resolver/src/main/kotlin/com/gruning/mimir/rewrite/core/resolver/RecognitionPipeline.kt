package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.GameIdentity
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * The raw facts a resolver has about a file BEFORE identity is established. Extracted cheaply first
 * (extension), then progressively enriched (serial read from the image, content hash) as the stronger,
 * costlier stages run. Everything here is local — no network read is ever performed to recognize a
 * file (posture manifest).
 *
 * @property fileName the file's display/base name (extension + region/disc tokens live here).
 * @property extension the lower-cased extension WITHOUT the dot (e.g. "chd", "nds", "m3u"); "" if none.
 * @property sizeBytes file size; a distinctness signal for multi-disc siblings.
 * @property serial an in-image serial/title-id if a stage has read one (e.g. PS `SLUS-xxxxx`); null until read.
 * @property contentHash a byte-exact content hash if computed; null until hashed (the strongest identity key).
 */
data class ResolutionInput(
    val fileName: String,
    val extension: String,
    val sizeBytes: Long = 0L,
    val serial: String? = null,
    val contentHash: String? = null,
)

/**
 * Stage 1 of recognition: map a file's extension to its platform. This is the cheapest, always-run
 * stage — the current product's classifier keys primarily off extension/folder . It CANNOT establish game *identity* (that needs serial/hash), only
 * which system the file belongs to.
 */
interface PlatformClassifier {
    /** The platform for a file, or null when the extension is unknown/ambiguous. */
    fun classify(input: ResolutionInput): PlatformId?
}

/**
 * Stage 2+: establish (or refine) a file's game IDENTITY from the strongest available evidence.
 * Implementations layer content-hash → catalog serial → filename tokens, never collapsing on a weak
 * signal alone (title-only match is never enough — the design constraint).
 */
interface IdentityResolver {
    /**
     * Resolve identity for a file already known to be on [platformId]. Returns the best identity the
     * available evidence supports; may be filename-only (weak) when no hash/serial is present.
     */
    fun resolve(platformId: PlatformId, input: ResolutionInput): GameIdentity
}

// Full file admission lives only in scan.ScanRecognizer, where path context, support role, size, and typed
// platform failures are all available. The former context-free DefaultRecognitionPipeline was unused and is
// intentionally retired: composing PlatformClassifier + IdentityResolver directly bypassed those gates.
