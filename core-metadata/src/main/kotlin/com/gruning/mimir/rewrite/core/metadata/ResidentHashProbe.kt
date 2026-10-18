package com.gruning.mimir.rewrite.core.metadata

import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface ResidentFingerprintResult {
    data class Fingerprinted(val evidence: RomFingerprint) : ResidentFingerprintResult
    data object Unrecognized : ResidentFingerprintResult
    data object Retryable : ResidentFingerprintResult
}

/**
 * Fingerprints a resident's file ON DEMAND — the file's bytes are read only when a caller actually needs
 * the hash (a metadata lookup, a DAT identity match, or an explicit "identify this game"), NEVER eagerly
 * for the whole library on the scan path (a multi-GB disc would stall the scan). Streams the file once via
 * [FileHasher] on [ioDispatcher], off the main thread.
 *
 * This is the bridge from the pure [FileHasher] to real files: the [ContentOpener] supplies the SAF /
 * file stream, and the resulting [ContentHash] is what lets the online path query by fingerprint (never
 * the filename) and what the local DAT identity source matches on.
 */
class ResidentHashProbe(
    private val opener: ContentOpener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The [ContentHash] of the file at [documentUri] (SAF) or [managedPath] (file), or null if unreadable. */
    suspend fun fingerprint(documentUri: String?, managedPath: String?): ContentHash? =
        usingBoundOpener { bound ->
            withContext(ioDispatcher) {
                val stream = try {
                    bound.open(documentUri, managedPath)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                } ?: return@withContext null
                try {
                    FileHasher.hash(stream)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
        }

    /**
     * The [ContentHash] fingerprinted the way [platformId]'s DAT expects (a provisional CHD header claim,
     * iNES-headerless, a single-ROM zip entry, or whole-file) — see [RomFingerprinter]. Production CHD
     * authority additionally requires [ChdPayloadVerifier]; this probe alone never promotes the header.
     */
    suspend fun fingerprintFor(platformId: String, documentUri: String?, managedPath: String?): ContentHash? =
        fingerprintEvidenceFor(platformId, documentUri, managedPath)?.datHash

    /** Format-aware DAT fingerprint plus byte-exact provenance from the same single stream pass. */
    suspend fun fingerprintEvidenceFor(
        platformId: String,
        documentUri: String?,
        managedPath: String?,
    ): RomFingerprint? = when (
        val result = fingerprintEvidenceResultFor(platformId, documentUri, managedPath)
    ) {
        is ResidentFingerprintResult.Fingerprinted -> result.evidence
        ResidentFingerprintResult.Unrecognized,
        ResidentFingerprintResult.Retryable,
        -> null
    }

    /**
     * Typed production seam: inability to open/read never becomes the same value as a completed but
     * unsupported container observation. Cancellation always escapes to the coroutine owner.
     */
    internal suspend fun fingerprintEvidenceResultFor(
        platformId: String,
        documentUri: String?,
        managedPath: String?,
        fileNameOverride: String? = null,
    ): ResidentFingerprintResult =
        usingBoundOpener { bound ->
            withContext(ioDispatcher) {
                val stream = try {
                    bound.open(documentUri, managedPath)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    null
                } ?: return@withContext ResidentFingerprintResult.Retryable
                val fileName = fileNameOverride ?: rawFileName(documentUri, managedPath)
                try {
                    val evidence = RomFingerprinter.fingerprintWithProvenance(platformId, fileName, stream)
                    if (evidence != null) {
                        ResidentFingerprintResult.Fingerprinted(evidence)
                    } else if (fileName.endsWith(".zip", ignoreCase = true)) {
                        // A damaged, over-budget or ambiguous archive is deliberately left retryable. The
                        // sweep cursor prevents it from starving later residents.
                        ResidentFingerprintResult.Retryable
                    } else {
                        ResidentFingerprintResult.Unrecognized
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    ResidentFingerprintResult.Retryable
                }
            }
        }

    private suspend fun <T> usingBoundOpener(block: suspend (ContentOpener) -> T): T =
        if (opener is CancellationBoundContentOpener) {
            block(opener)
        } else {
            withContentCancellation { scope -> block(opener.boundTo(scope)) }
        }
}

/**
 * The resident's own file name (no directory), decoded from whichever of [documentUri] / [managedPath] is
 * set — used ONLY so [RomFingerprinter] can recognise a `.zip` container; it never sees a raw path
 * otherwise. Empty (never null) when neither is set, so the zip check downstream safely reads "not a zip"
 * without a null-check at the call site — matching today's behavior (open would already have failed).
 */
private fun rawFileName(documentUri: String?, managedPath: String?): String {
    val s = documentUri ?: managedPath ?: return ""
    val decoded = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    return decoded.substringAfterLast('/').substringAfterLast('\\')
}
