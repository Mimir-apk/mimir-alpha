package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ChdChainPayloadVerifier
import com.gruning.mimir.rewrite.core.metadata.ChdChainResident
import com.gruning.mimir.rewrite.core.metadata.ChdHeaderEvidence
import com.gruning.mimir.rewrite.core.metadata.ChdParentChainDiscovery
import com.gruning.mimir.rewrite.core.metadata.ChdResidentHeaderRead
import com.gruning.mimir.rewrite.core.metadata.ChdVerificationBudget
import com.gruning.mimir.rewrite.core.metadata.ChdVerificationNode
import com.gruning.mimir.rewrite.core.metadata.ContainerIdentityReason
import com.gruning.mimir.rewrite.core.metadata.ContainerIdentityResult
import com.gruning.mimir.rewrite.core.metadata.ContentOpener
import com.gruning.mimir.rewrite.core.metadata.NativeSeekablePath
import com.gruning.mimir.rewrite.core.metadata.RomFingerprinter
import com.gruning.mimir.rewrite.core.metadata.VerifiedChdChainSelection
import com.gruning.mimir.rewrite.core.metadata.VerifiedChdIdentity
import com.gruning.mimir.rewrite.core.metadata.withContentCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Physical CHD location supplied by the app's existing Room inventory. These fields locate current bytes;
 * none is persisted as RetroAchievements identity.
 */
data class RetroAchievementsChdResident(
    val entryKey: String,
    val sourceRootUri: String,
    val relativePath: String,
    val documentUri: String?,
    val managedPath: String?,
    val sizeBytes: Long = 0L,
)

internal sealed interface RetroAchievementsChdHashResult {
    data class Hashed(val md5: String?) : RetroAchievementsChdHashResult
    data object FileUnavailable : RetroAchievementsChdHashResult
    data object IdentificationUnavailable : RetroAchievementsChdHashResult
}

internal data class VerifiedNativeChdHash(
    val identity: VerifiedChdIdentity,
    val md5: String?,
)

/**
 * Fresh, focus-scoped differential-CHD discovery for RA. The engine never reuses the enricher's sweep
 * catalog: availability and descriptor validity may have changed since that background pass.
 */
class RetroAchievementsChdHashEngine(
    private val residentInventory: suspend (limit: Int) -> List<RetroAchievementsChdResident>,
    private val opener: ContentOpener,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    internal var verifiedChainDiscovery:
        suspend (
            opener: ContentOpener,
            target: ChdChainResident,
            header: ChdHeaderEvidence,
            budget: ChdVerificationBudget,
        ) -> ContainerIdentityResult<VerifiedChdChainSelection> = {
            boundOpener,
            target,
            header,
            budget,
        ->
        ChdParentChainDiscovery(
            inventory = {
                residentInventory(ChdParentChainDiscovery.MAX_RESIDENTS + 1).map {
                    it.toInternal()
                }
            },
            headerReader = { resident -> readHeader(boundOpener, resident) },
            holderOpener = { resident ->
                boundOpener.openNativeSeekablePath(resident.documentUri, resident.managedPath)
            },
            nanoTime = nanoTime,
        ).resolveVerifiedChain(target, header, budget)
    }

    internal var verifiedChainHasher:
        suspend (
            consoleId: Int,
            nodes: List<ChdVerificationNode>,
            expectedIdentity: VerifiedChdIdentity,
            budget: ChdVerificationBudget,
        ) -> ContainerIdentityResult<VerifiedNativeChdHash> = {
            consoleId,
            nodes,
            expectedIdentity,
            budget,
        ->
            ChdChainPayloadVerifier.withVerifiedNativeChain(
                nodes,
                expectedIdentity = expectedIdentity,
                budget = budget,
            ) { borrowed ->
                VerifiedNativeChdHash(
                    identity = borrowed.identity,
                    md5 = RetroAchievementsNativeHash.hashChain(
                        consoleId = consoleId,
                        targetPath = borrowed.targetPath,
                        nativeChainHandle = borrowed.nativeHandle,
                    ),
                )
            }
        }

    internal suspend fun hash(
        consoleId: Int,
        target: RetroAchievementsChdResident,
    ): RetroAchievementsChdHashResult = withContentCancellation { scope ->
        val bound = opener.boundTo(scope)
        hashWith(bound, consoleId, target)
    }

    private suspend fun hashWith(
        opener: ContentOpener,
        consoleId: Int,
        target: RetroAchievementsChdResident,
    ): RetroAchievementsChdHashResult {
        currentCoroutineContext().ensureActive()
        val targetResident = target.toInternal()
        val targetHeader = when (val read = readHeader(opener, targetResident)) {
            is ChdResidentHeaderRead.Parsed -> read.header
            ChdResidentHeaderRead.NotChd,
            ChdResidentHeaderRead.Unreadable,
            -> return RetroAchievementsChdHashResult.FileUnavailable
        }
        if (targetHeader.parentSha1 == null) {
            val holder = opener.openNativeSeekablePath(target.documentUri, target.managedPath)
                ?: return RetroAchievementsChdHashResult.FileUnavailable
            return holder.use {
                RetroAchievementsChdHashResult.Hashed(
                    RetroAchievementsNativeHash.hash(consoleId, it.path),
                )
            }
        }

        val budget = ChdVerificationBudget.forResolve(nanoTime = nanoTime)
        val selected = when (
            val result = verifiedChainDiscovery(opener, targetResident, targetHeader, budget)
        ) {
            is ContainerIdentityResult.Identified -> result.value
            is ContainerIdentityResult.PolicyAbstain ->
                return RetroAchievementsChdHashResult.IdentificationUnavailable
            is ContainerIdentityResult.NeedsReview,
            is ContainerIdentityResult.RetryableFailure,
            -> return RetroAchievementsChdHashResult.FileUnavailable
        }

        val holders = ArrayList<NativeSeekablePath>(selected.nodesRootFirst.size)
        try {
            val nodes = ArrayList<ChdVerificationNode>(selected.nodesRootFirst.size)
            selected.nodesRootFirst.forEach { node ->
                currentCoroutineContext().ensureActive()
                if (!budget.hasAdmissionFloor()) {
                    return RetroAchievementsChdHashResult.FileUnavailable
                }
                val holder = opener.openNativeSeekablePath(
                    node.resident.documentUri,
                    node.resident.managedPath,
                ) ?: return RetroAchievementsChdHashResult.FileUnavailable
                holders += holder
                nodes += ChdVerificationNode(holder.path, node.header)
            }
            return when (
                val hashed = verifiedChainHasher(
                    consoleId,
                    nodes,
                    selected.identity,
                    budget,
                )
            ) {
                is ContainerIdentityResult.Identified -> {
                    if (hashed.value.identity != selected.identity) {
                        RetroAchievementsChdHashResult.FileUnavailable
                    } else {
                        RetroAchievementsChdHashResult.Hashed(hashed.value.md5)
                    }
                }
                is ContainerIdentityResult.PolicyAbstain ->
                    RetroAchievementsChdHashResult.IdentificationUnavailable
                is ContainerIdentityResult.NeedsReview,
                is ContainerIdentityResult.RetryableFailure,
                -> RetroAchievementsChdHashResult.FileUnavailable
            }
        } finally {
            holders.indices.reversed().forEach { index ->
                runCatching { holders[index].close() }
            }
        }
    }

    private suspend fun readHeader(
        opener: ContentOpener,
        resident: ChdChainResident,
    ): ChdResidentHeaderRead {
        currentCoroutineContext().ensureActive()
        val random = opener.openRandomAccess(resident.documentUri, resident.managedPath)
            ?: return ChdResidentHeaderRead.Unreadable
        return random.use { content ->
            if (content.length < CHD_HEADER_BYTES) return@use ChdResidentHeaderRead.NotChd
            val bytes = ByteArray(CHD_HEADER_BYTES)
            var total = 0
            while (total < bytes.size) {
                currentCoroutineContext().ensureActive()
                val read = try {
                    content.readAt(total.toLong(), bytes, total, bytes.size - total)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    return@use ChdResidentHeaderRead.Unreadable
                }
                if (read <= 0) return@use ChdResidentHeaderRead.Unreadable
                total += read
            }
            RomFingerprinter.chdHeaderEvidence(bytes, bytes.size)
                ?.let(ChdResidentHeaderRead::Parsed)
                ?: ChdResidentHeaderRead.NotChd
        }
    }

    private fun RetroAchievementsChdResident.toInternal(): ChdChainResident = ChdChainResident(
        entryKey = entryKey,
        sourceRootUri = sourceRootUri,
        relativePath = relativePath,
        documentUri = documentUri,
        managedPath = managedPath,
    )

    private companion object {
        const val CHD_HEADER_BYTES = 132
    }
}
