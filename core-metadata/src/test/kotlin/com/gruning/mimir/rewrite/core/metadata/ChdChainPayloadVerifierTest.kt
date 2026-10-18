package com.gruning.mimir.rewrite.core.metadata

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChdChainPayloadVerifierTest {

    @Test
    fun `root middle and child are independently recomputed before target identifies`() = runBlocking {
        val root = node("/renamed-root.chd", "root-payload".toByteArray(), null)
        val middle = node(
            "/opaque-middle.chd",
            "middle-payload".toByteArray(),
            root.header.combinedSha1,
        )
        val child = node(
            "/target.chd",
            "child-payload".toByteArray(),
            middle.header.combinedSha1,
        )
        val session = FakeChainSession(listOf(root, middle, child))

        val result = ChdChainPayloadVerifier.verify(
            chain = listOf(root, middle, child),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
        )

        result as ContainerIdentityResult.Identified
        assertEquals(child.header.combinedSha1, result.value.combinedSha1)
        assertEquals(3, result.value.chainDepth)
        assertEquals(setOf(0, 1, 2), session.hunkReads.map { it.first }.toSet())
        assertTrue(session.closed)
    }

    @Test
    fun `verified native chain remains borrowed through block then closes exactly once`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        val child = node("/child.chd", "child".toByteArray(), root.header.combinedSha1)
        val session = FakeChainSession(
            declared = listOf(root, child),
            nativeHandle = 73L,
        )

        val result = ChdChainPayloadVerifier.withVerifiedNativeChain(
            chain = listOf(root, child),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
        ) { borrowed ->
            assertFalse("native owner must outlive the borrowed RA hash", session.closed)
            assertEquals(73L, borrowed.nativeHandle)
            assertEquals("/child.chd", borrowed.targetPath)
            "canonical-md5"
        }

        assertEquals(ContainerIdentityResult.Identified("canonical-md5"), result)
        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `changed identity on second verification closes before native hash block`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        val child = node("/child.chd", "child".toByteArray(), root.header.combinedSha1)
        val session = FakeChainSession(
            declared = listOf(root, child),
            nativeHandle = 81L,
        )
        var hashCalls = 0
        val staleIdentity = VerifiedChdIdentity(
            version = child.header.version,
            logicalBytes = child.header.logicalBytes,
            rawSha1 = child.header.rawSha1,
            combinedSha1 = "f".repeat(40),
            chainDepth = 2,
            compressionTags = child.header.compressionTags,
        )

        val result = ChdChainPayloadVerifier.withVerifiedNativeChain(
            chain = listOf(root, child),
            expectedIdentity = staleIdentity,
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
        ) {
            hashCalls++
            "must-not-run"
        }

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            result,
        )
        assertEquals("mutation must stop before rcheevos borrows the chain", 0, hashCalls)
        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `throwing borrowed-chain consumer still closes the native owner once`() {
        val root = node("/root.chd", "root".toByteArray(), null)
        val session = FakeChainSession(listOf(root), nativeHandle = 91L)

        val failure = runCatching {
            runBlocking {
                ChdChainPayloadVerifier.withVerifiedNativeChain(
                    chain = listOf(root),
                    backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
                ) {
                    throw IllegalStateException("consumer failed")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `forged parent body blocks before child can inherit its header claim`() = runBlocking {
        val declaredRoot = node("/root.chd", "declared-root".toByteArray(), null)
        val child = node(
            "/child.chd",
            "child".toByteArray(),
            declaredRoot.header.combinedSha1,
        )
        val session = FakeChainSession(
            declared = listOf(declaredRoot, child),
            actualPayloads = listOf("forged-root".toByteArray(), "child".toByteArray()),
        )

        val result = ChdChainPayloadVerifier.verify(
            listOf(declaredRoot, child),
            ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            result,
        )
        assertTrue(session.hunkReads.all { it.first == 0 })
        assertTrue(session.closed)
    }

    @Test
    fun `wrong ordering cycle and depth overflow fail before native open`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        val wrong = node("/wrong.chd", "wrong".toByteArray(), "f".repeat(40))
        var opened = false
        val backend = ChdChainVerifierBackend {
            opened = true
            error("preflight must fail first")
        }

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED),
            ChdChainPayloadVerifier.verify(listOf(root, wrong), backend),
        )
        assertFalse(opened)

        val cycle = root.copy(
            path = "/cycle.chd",
            header = root.header.copy(parentSha1 = root.header.combinedSha1),
        )
        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED),
            ChdChainPayloadVerifier.verify(listOf(cycle), backend),
        )

        val tooDeep = buildList {
            var parent: String? = null
            repeat(ChdChainPayloadVerifier.MAX_CHAIN_DEPTH + 1) { index ->
                val next = node("/$index.chd", "payload-$index".toByteArray(), parent)
                add(next)
                parent = next.header.combinedSha1
            }
        }
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            ChdChainPayloadVerifier.verify(tooDeep, backend),
        )
        assertFalse(opened)
    }

    @Test
    fun `per-node hunk and raw map budgets fail before native chain open`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        var opened = false
        val backend = ChdChainVerifierBackend {
            opened = true
            error("hostile chain header must never reach native open")
        }

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            ChdChainPayloadVerifier.verify(
                listOf(root.copy(header = root.header.copy(hunkBytes = 8 * 1024 * 1024 + 1))),
                backend,
            ),
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            ChdChainPayloadVerifier.verify(
                listOf(root.copy(header = root.header.copy(declaredHunkCount = 1_000_001L))),
                backend,
            ),
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            ChdChainPayloadVerifier.verify(
                listOf(root.copy(header = root.header.copy(declaredHunkCount = 2L))),
                backend,
            ),
        )
        assertFalse(opened)
    }

    @Test
    fun `missing parent remains retryable and cancellation closes the complete session`() {
        val root = node("/root.chd", "root".toByteArray(), null)
        val child = node("/child.chd", "child".toByteArray(), root.header.combinedSha1)
        var incompleteOpened = false
        val incomplete = runBlocking {
            ChdChainPayloadVerifier.verify(
                listOf(child),
                ChdChainVerifierBackend {
                    incompleteOpened = true
                    error("incomplete chain must never reach native open")
                },
            )
        }
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.PARENT_REQUIRED),
            incomplete,
        )
        assertFalse(incompleteOpened)

        val missing = runBlocking {
            ChdChainPayloadVerifier.verify(
                listOf(root, child),
                ChdChainVerifierBackend {
                    ChdChainOpen.Failed(
                        result = ContainerIdentityResult.RetryableFailure(
                            ContainerIdentityReason.PARENT_REQUIRED,
                        ),
                        failingNodeIndex = null,
                    )
                },
            )
        }
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.PARENT_REQUIRED),
            missing,
        )

        val session = FakeChainSession(
            declared = listOf(root, child),
            cancelAtNode = 1,
        )
        val failure = runCatching {
            runBlocking {
                ChdChainPayloadVerifier.verify(
                    listOf(root, child),
                    ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(session.closed)
    }

    @Test
    fun `native info disagreement and aggregate budget fail closed`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        val child = node("/child.chd", "child".toByteArray(), root.header.combinedSha1)
        val mismatched = FakeChainSession(listOf(root, child), mismatchNode = 1)

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            ChdChainPayloadVerifier.verify(
                listOf(root, child),
                ChdChainVerifierBackend { ChdChainOpen.Opened(mismatched) },
            ),
        )
        assertTrue(mismatched.closed)

        val oversizedRoot = root.copy(
            header = root.header.copy(logicalBytes = 16L * 1024 * 1024 * 1024),
        )
        val oversizedChild = child.copy(
            header = child.header.copy(
                logicalBytes = 16L * 1024 * 1024 * 1024,
                parentSha1 = oversizedRoot.header.combinedSha1,
            ),
        )
        val oversized = listOf(
            oversizedRoot,
            oversizedChild,
            oversizedChild.copy(
                path = "/third.chd",
                header = oversizedChild.header.copy(
                    combinedSha1 = "3".repeat(40),
                    parentSha1 = oversizedChild.header.combinedSha1,
                ),
            ),
            oversizedChild.copy(
                path = "/fourth.chd",
                header = oversizedChild.header.copy(
                    combinedSha1 = "4".repeat(40),
                    parentSha1 = "3".repeat(40),
                ),
            ),
            oversizedChild.copy(
                path = "/fifth.chd",
                header = oversizedChild.header.copy(
                    combinedSha1 = "5".repeat(40),
                    parentSha1 = "4".repeat(40),
                ),
            ),
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            ChdChainPayloadVerifier.verify(
                oversized,
                ChdChainVerifierBackend { error("aggregate preflight must refuse") },
            ),
        )
    }

    @Test
    fun `wall budget refuses retryably with budget exhausted provenance`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        var now = 0L
        val session = object : ChdChainReadSession {
            override val nodes = listOf(
                ChdChainNodeInfo(
                    version = root.header.version,
                    logicalBytes = root.header.logicalBytes,
                    hunkBytes = root.header.hunkBytes,
                    hunkCount = (
                        (root.header.logicalBytes + root.header.hunkBytes - 1L) /
                            root.header.hunkBytes
                        ).toInt(),
                    rawSha1 = root.header.rawSha1,
                    combinedSha1 = root.header.combinedSha1,
                    parentSha1 = root.header.parentSha1,
                    compressionTags = root.header.compressionTags,
                ),
            )
            var closed = false

            override fun readHunk(
                nodeIndex: Int,
                hunkIndex: Int,
                destination: ByteArray,
            ): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                destination.fill(0)
                "root".toByteArray().copyInto(destination, 0, 0, minOf(4, destination.size))
                return ChdHunkRead.OK
            }

            override fun readMetadata(
                nodeIndex: Int,
                metadataIndex: Int,
                maxBytes: Int,
            ): ChdMetadataRead = ChdMetadataRead.End

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val verification = ChdChainPayloadVerifier.verifyChain(
            chain = listOf(root),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            verification.result,
        )
        assertTrue(verification.budgetExhausted)
        assertTrue(session.closed)
    }

    @Test
    fun `chain open that consumes deadline closes session and carries expiry provenance`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        var now = 0L
        val session = FakeChainSession(listOf(root))
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val verification = ChdChainPayloadVerifier.verifyChain(
            chain = listOf(root),
            backend = ChdChainVerifierBackend {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                ChdChainOpen.Opened(session)
            },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            verification.result,
        )
        assertTrue(verification.budgetExhausted)
        assertTrue(session.closed)
    }

    @Test
    fun `chain verifier refuses after slow final End read crosses deadline`() = runBlocking {
        val payload = "root".toByteArray()
        val raw = sha1(payload)
        val metadataBytes = "meta".toByteArray()
        val combined = overallV5(raw, metadataBytes)
        val root = ChdVerificationNode(
            path = "/root.chd",
            header = ChdHeaderEvidence(
                version = 5,
                logicalBytes = payload.size.toLong(),
                hunkBytes = 4,
                rawSha1 = raw,
                combinedSha1 = combined,
                parentSha1 = null,
                compressionTags = listOf(0, 0, 0, 0),
            ),
        )
        var now = 0L
        val session = object : ChdChainReadSession {
            override val nodes = listOf(
                ChdChainNodeInfo(
                    version = 5,
                    logicalBytes = payload.size.toLong(),
                    hunkBytes = 4,
                    hunkCount = 1,
                    rawSha1 = raw,
                    combinedSha1 = combined,
                    parentSha1 = null,
                    compressionTags = listOf(0, 0, 0, 0),
                ),
            )
            var closed = false

            override fun readHunk(
                nodeIndex: Int,
                hunkIndex: Int,
                destination: ByteArray,
            ): ChdHunkRead {
                destination.fill(0)
                payload.copyInto(destination, 0, 0, minOf(payload.size, destination.size))
                return ChdHunkRead.OK
            }

            override fun readMetadata(
                nodeIndex: Int,
                metadataIndex: Int,
                maxBytes: Int,
            ): ChdMetadataRead {
                if (metadataIndex == 0) {
                    return ChdMetadataRead.Checksummed(tag("CHT2"), metadataBytes)
                }
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdMetadataRead.End
            }

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val verification = ChdChainPayloadVerifier.verifyChain(
            chain = listOf(root),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            verification.result,
        )
        assertTrue(verification.budgetExhausted)
        assertTrue(session.closed)
    }

    @Test
    fun `slow Failed PolicyAbstain becomes resource refused with no prune index when budget expires`() =
        runBlocking {
            val root = node("/root.chd", "root".toByteArray(), null)
            var now = 0L
            val budget = ChdVerificationBudget.forResolve(
                nanoTime = { now },
                absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
            )

            val verification = ChdChainPayloadVerifier.verifyChain(
                chain = listOf(root),
                backend = ChdChainVerifierBackend {
                    now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                    ChdChainOpen.Failed(
                        result = ContainerIdentityResult.PolicyAbstain(
                            ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                        ),
                        failingNodeIndex = 0,
                    )
                },
                budget = budget,
            )

            assertEquals(
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
                verification.result,
            )
            assertTrue(verification.budgetExhausted)
            assertEquals(null, verification.openFailureNodeIndex)
        }

    @Test
    fun `slow hunk Unsupported becomes resource refused when budget expires`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        var now = 0L
        val session = object : ChdChainReadSession {
            override val nodes = listOf(
                ChdChainNodeInfo(
                    version = root.header.version,
                    logicalBytes = root.header.logicalBytes,
                    hunkBytes = root.header.hunkBytes,
                    hunkCount = (
                        (root.header.logicalBytes + root.header.hunkBytes - 1L) /
                            root.header.hunkBytes
                        ).toInt(),
                    rawSha1 = root.header.rawSha1,
                    combinedSha1 = root.header.combinedSha1,
                    parentSha1 = root.header.parentSha1,
                    compressionTags = root.header.compressionTags,
                ),
            )
            var closed = false

            override fun readHunk(
                nodeIndex: Int,
                hunkIndex: Int,
                destination: ByteArray,
            ): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdHunkRead.UNSUPPORTED
            }

            override fun readMetadata(
                nodeIndex: Int,
                metadataIndex: Int,
                maxBytes: Int,
            ): ChdMetadataRead = ChdMetadataRead.End

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val verification = ChdChainPayloadVerifier.verifyChain(
            chain = listOf(root),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            verification.result,
        )
        assertTrue(verification.budgetExhausted)
        assertTrue(session.closed)
    }

    @Test
    fun `slow metadata Unsupported becomes resource refused when budget expires`() = runBlocking {
        val payload = "root".toByteArray()
        val raw = sha1(payload)
        val metadataBytes = "meta".toByteArray()
        val combined = overallV5(raw, metadataBytes)
        val root = ChdVerificationNode(
            path = "/root.chd",
            header = ChdHeaderEvidence(
                version = 5,
                logicalBytes = payload.size.toLong(),
                hunkBytes = 4,
                rawSha1 = raw,
                combinedSha1 = combined,
                parentSha1 = null,
                compressionTags = listOf(0, 0, 0, 0),
            ),
        )
        var now = 0L
        val session = object : ChdChainReadSession {
            override val nodes = listOf(
                ChdChainNodeInfo(
                    version = 5,
                    logicalBytes = payload.size.toLong(),
                    hunkBytes = 4,
                    hunkCount = 1,
                    rawSha1 = raw,
                    combinedSha1 = combined,
                    parentSha1 = null,
                    compressionTags = listOf(0, 0, 0, 0),
                ),
            )
            var closed = false

            override fun readHunk(
                nodeIndex: Int,
                hunkIndex: Int,
                destination: ByteArray,
            ): ChdHunkRead {
                destination.fill(0)
                payload.copyInto(destination, 0, 0, minOf(payload.size, destination.size))
                return ChdHunkRead.OK
            }

            override fun readMetadata(
                nodeIndex: Int,
                metadataIndex: Int,
                maxBytes: Int,
            ): ChdMetadataRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdMetadataRead.Unsupported
            }

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val verification = ChdChainPayloadVerifier.verifyChain(
            chain = listOf(root),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            verification.result,
        )
        assertTrue(verification.budgetExhausted)
        assertTrue(session.closed)
    }

    @Test
    fun `open failure index is surfaced for discovery pruning and sanitized when out of range`() = runBlocking {
        val root = node("/root.chd", "root".toByteArray(), null)
        val child = node("/child.chd", "child".toByteArray(), root.header.combinedSha1)

        val attributed = ChdChainPayloadVerifier.verifyChain(
            listOf(root, child),
            ChdChainVerifierBackend {
                ChdChainOpen.Failed(
                    result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                    failingNodeIndex = 1,
                )
            },
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            attributed.result,
        )
        assertEquals(1, attributed.openFailureNodeIndex)

        val unattributed = ChdChainPayloadVerifier.verifyChain(
            listOf(root, child),
            ChdChainVerifierBackend {
                ChdChainOpen.Failed(
                    result = ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.IO_RETRYABLE,
                    ),
                    failingNodeIndex = 7,
                )
            },
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE),
            unattributed.result,
        )
        assertEquals(null, unattributed.openFailureNodeIndex)
    }

    private fun node(path: String, payload: ByteArray, parentSha1: String?): ChdVerificationNode {
        val raw = sha1(payload)
        return ChdVerificationNode(
            path = path,
            header = ChdHeaderEvidence(
                version = 3,
                logicalBytes = payload.size.toLong(),
                hunkBytes = 4,
                rawSha1 = raw,
                combinedSha1 = raw,
                parentSha1 = parentSha1,
                compressionTags = listOf(0, 0, 0, 0),
            ),
        )
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun tag(value: String): Int {
        require(value.length == 4)
        return value.fold(0) { result, character -> (result shl 8) or character.code }
    }

    private fun overallV5(rawSha1: String, metadataBytes: ByteArray): String {
        val record = ByteArray(24)
        repeat(4) { index -> record[index] = (tag("CHT2") ushr ((3 - index) * 8)).toByte() }
        MessageDigest.getInstance("SHA-1").digest(metadataBytes).copyInto(record, 4)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(hex(rawSha1))
        digest.update(record)
        return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private class FakeChainSession(
        declared: List<ChdVerificationNode>,
        private val actualPayloads: List<ByteArray> = declared.map {
            when (it.path) {
                "/renamed-root.chd" -> "root-payload".toByteArray()
                "/opaque-middle.chd" -> "middle-payload".toByteArray()
                "/target.chd" -> "child-payload".toByteArray()
                "/root.chd" -> "root".toByteArray()
                "/child.chd" -> "child".toByteArray()
                else -> ByteArray(it.header.logicalBytes.toInt())
            }
        },
        private val cancelAtNode: Int? = null,
        mismatchNode: Int? = null,
        override val nativeHandle: Long = 0L,
    ) : ChdChainReadSession {
        override val nodes: List<ChdChainNodeInfo> = declared.mapIndexed { index, node ->
            ChdChainNodeInfo(
                version = node.header.version,
                logicalBytes = node.header.logicalBytes,
                hunkBytes = node.header.hunkBytes,
                hunkCount = (
                    (node.header.logicalBytes + node.header.hunkBytes - 1L) /
                        node.header.hunkBytes
                    ).toInt(),
                rawSha1 = node.header.rawSha1,
                combinedSha1 = if (index == mismatchNode) {
                    "e".repeat(40)
                } else {
                    node.header.combinedSha1
                },
                parentSha1 = node.header.parentSha1,
                compressionTags = node.header.compressionTags,
            )
        }
        val hunkReads = mutableListOf<Pair<Int, Int>>()
        var closed = false
            private set
        var closeCount = 0
            private set

        override fun readHunk(
            nodeIndex: Int,
            hunkIndex: Int,
            destination: ByteArray,
        ): ChdHunkRead {
            if (nodeIndex == cancelAtNode) throw CancellationException("chain cancellation")
            hunkReads += nodeIndex to hunkIndex
            destination.fill(0)
            val payload = actualPayloads[nodeIndex]
            val start = hunkIndex * nodes[nodeIndex].hunkBytes
            if (start >= payload.size) return ChdHunkRead.MALFORMED
            payload.copyInto(
                destination = destination,
                startIndex = start,
                endIndex = minOf(payload.size, start + destination.size),
            )
            return ChdHunkRead.OK
        }

        override fun readMetadata(
            nodeIndex: Int,
            metadataIndex: Int,
            maxBytes: Int,
        ): ChdMetadataRead = ChdMetadataRead.End

        override fun close() {
            closed = true
            closeCount++
        }
    }
}
