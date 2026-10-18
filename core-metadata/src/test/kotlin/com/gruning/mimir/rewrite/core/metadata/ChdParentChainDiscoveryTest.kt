package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChdParentChainDiscoveryTest {

    @Test
    fun `renamed parent is discovered by digest and fully reverified end to end`() = runBlocking {
        val parentPayload = "parent-payload".toByteArray()
        val childPayload = "child-payload".toByteArray()
        val parentDigest = sha1(parentPayload)
        val childDigest = sha1(childPayload)
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val discovery = ChdParentChainDiscovery(
            inventory = {
                listOf(
                    resident("zzz::completely-unrelated-name.tmp.chd"),
                    target,
                )
            },
            headerReader = { chd ->
                when (chd.entryKey) {
                    "zzz::completely-unrelated-name.tmp.chd" ->
                        parsed(header(combined = parentDigest, parent = null, logicalBytes = parentPayload.size.toLong()))
                    target.entryKey ->
                        parsed(header(combined = childDigest, parent = parentDigest, logicalBytes = childPayload.size.toLong()))
                    else -> ChdResidentHeaderRead.NotChd
                }
            },
            holderOpener = holders::open,
            verifier = { nodes, budget ->
                ChdChainPayloadVerifier.verifyChain(
                    chain = nodes,
                    backend = {
                        ChdChainOpen.Opened(
                            PayloadChainSession(nodes, listOf(parentPayload, childPayload), holders),
                        )
                    },
                    budget = budget,
                )
            },
        )

        val result = discovery.resolve(
            target,
            header(combined = childDigest, parent = parentDigest, logicalBytes = childPayload.size.toLong()),
        )

        result as ContainerIdentityResult.Identified
        assertEquals(childDigest, result.value.combinedSha1)
        assertEquals(2, result.value.chainDepth)
        assertEquals(
            listOf(
                "open:zzz::completely-unrelated-name.tmp.chd",
                "open:roms::game.chd",
                "native-closed",
                "close:roms::game.chd",
                "close:zzz::completely-unrelated-name.tmp.chd",
            ),
            holders.events,
        )
    }

    @Test
    fun `wrong parent name is never selected and true absence stays retryable`() = runBlocking {
        val holders = HolderLog()
        var verifications = 0
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = digest("target"), parent = digest("real-parent"))
        val discovery = ChdParentChainDiscovery(
            inventory = {
                listOf(
                    target,
                    // Named like the parent, but its header claims a different overall digest.
                    resident("roms::game (parent).chd"),
                    // Named like the parent, but not a CHD at all.
                    resident("roms::game-parent-lookalike.chd"),
                )
            },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    "roms::game (parent).chd" -> parsed(header(combined = digest("unrelated"), parent = null))
                    else -> ChdResidentHeaderRead.NotChd
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ ->
                verifications++
                error("no candidate carries the required digest, so nothing may be verified")
            },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.PARENT_REQUIRED),
            discovery.resolve(target, targetHeader),
        )
        assertEquals(0, verifications)
        assertTrue(holders.events.isEmpty())
    }

    @Test
    fun `duplicate equivalent copies converge after one verification`() = runBlocking {
        val fixture = SingleParentFixture(
            parentKeys = listOf("a::parent-copy-one.chd", "b::parent-copy-two.chd"),
        )
        val verified = mutableListOf<List<String>>()
        val discovery = fixture.discovery { nodes, _ ->
            verified += nodes.keys()
            identified(depth = nodes.size, combined = fixture.targetDigest)
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(fixture.targetDigest, result.value.combinedSha1)
        // Structurally equivalent copies converge; the second copy is never opened.
        assertEquals(listOf(listOf("a::parent-copy-one.chd", "roms::game.chd")), verified)
    }

    @Test
    fun `settled selection carries the exact verified residents root first`() = runBlocking {
        val fixture = SingleParentFixture(
            parentKeys = listOf("a::parent-copy-one.chd", "b::parent-copy-two.chd"),
        )
        val discovery = fixture.discovery { nodes, _ ->
            identified(depth = nodes.size, combined = fixture.targetDigest)
        }

        val result = discovery.resolveVerifiedChain(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(
            listOf("a::parent-copy-one.chd", fixture.target.entryKey),
            result.value.nodesRootFirst.map { it.resident.entryKey },
        )
        assertEquals(2, result.value.identity.chainDepth)
    }

    @Test
    fun `canonical p2 lexical order cannot steer equivalent parent selection`() = runBlocking {
        val targetDigest = digest("p2-target")
        val parentDigest = digest("p2-parent")
        val target = physicalResident("target", "game.chd")
        val targetHeader = header(combined = targetDigest, parent = parentDigest)
        val physicalFirst = physicalResident("aa", "parent.chd")
        val physicalSecond = physicalResident("z", "parent.chd")
        assertTrue(physicalFirst.entryKey > physicalSecond.entryKey)
        val verified = mutableListOf<List<String>>()
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(physicalSecond, target, physicalFirst) },
            headerReader = { resident ->
                if (resident.entryKey == target.entryKey) {
                    parsed(targetHeader)
                } else {
                    parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = HolderLog()::open,
            verifier = { nodes, _ ->
                verified += nodes.keys()
                identified(depth = nodes.size, combined = targetDigest)
            },
        )

        assertTrue(discovery.resolve(target, targetHeader) is ContainerIdentityResult.Identified)
        assertEquals(listOf(physicalFirst.entryKey, target.entryKey), verified.single())
    }

    @Test
    fun `corrupt duplicate copy falls through to its equivalent copy`() = runBlocking {
        val fixture = SingleParentFixture(
            parentKeys = listOf("a::parent-corrupt.chd", "b::parent-good.chd"),
        )
        val verified = mutableListOf<List<String>>()
        val discovery = fixture.discovery { nodes, _ ->
            verified += nodes.keys()
            if (nodes.keys().first() == "a::parent-corrupt.chd") {
                ChdChainVerification(
                    result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                    openFailureNodeIndex = 0,
                )
            } else {
                identified(depth = nodes.size, combined = fixture.targetDigest)
            }
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(2, verified.size)
        assertEquals("b::parent-good.chd", verified.last().first())
    }

    @Test
    fun `divergent verified structures are ambiguous`() = runBlocking {
        val fixture = DivergentFixture()
        val discovery = fixture.discovery { nodes, _ ->
            identified(depth = nodes.size, combined = fixture.targetDigest)
        }

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS),
            discovery.resolve(fixture.target, fixture.targetHeader),
        )
    }

    @Test
    fun `divergent claim that fails verification does not create ambiguity`() = runBlocking {
        val fixture = DivergentFixture()
        val discovery = fixture.discovery { nodes, _ ->
            if (nodes.size == 2) {
                identified(depth = 2, combined = fixture.targetDigest)
            } else {
                ChdChainVerification(
                    result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                )
            }
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(2, result.value.chainDepth)
    }

    @Test
    fun `unreadable resident forces retry when no chain verifies`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = digest("target"), parent = digest("parent"))
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("roms::maybe-the-parent.chd")) },
            headerReader = { chd ->
                if (chd.entryKey == target.entryKey) {
                    parsed(targetHeader)
                } else {
                    ChdResidentHeaderRead.Unreadable
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("nothing verifiable was enumerated") },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE),
            discovery.resolve(target, targetHeader),
        )
    }

    @Test
    fun `verified chain survives an unrelated unreadable resident`() = runBlocking {
        val fixture = SingleParentFixture(
            parentKeys = listOf("a::parent.chd"),
            unreadableKeys = listOf("z::detached-volume.chd"),
        )
        val discovery = fixture.discovery { nodes, _ ->
            identified(depth = nodes.size, combined = fixture.targetDigest)
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        assertTrue(result is ContainerIdentityResult.Identified)
    }

    @Test
    fun `cycle evidence fails closed as malformed review`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val digestA = digest("node-a")
        val digestB = digest("node-b")
        val targetHeader = header(combined = digest("target"), parent = digestA)
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("a::a.chd"), resident("b::b.chd")) },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    "a::a.chd" -> parsed(header(combined = digestA, parent = digestB))
                    else -> parsed(header(combined = digestB, parent = digestA))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("a cyclic ancestry must never reach native verification") },
        )

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED),
            discovery.resolve(target, targetHeader),
        )
    }

    @Test
    fun `resident budget refuses before any header is read`() = runBlocking {
        val holders = HolderLog()
        var headerReads = 0
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = digest("target"), parent = digest("parent"))
        val discovery = ChdParentChainDiscovery(
            inventory = {
                (0..ChdParentChainDiscovery.MAX_RESIDENTS).map { resident("bulk::$it.chd") }
            },
            headerReader = {
                headerReads++
                ChdResidentHeaderRead.NotChd
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("an over-budget inventory must refuse before verification") },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, targetHeader),
        )
        assertEquals(0, headerReads)
    }

    @Test
    fun `candidate budget refuses only digests the target needs`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val neededDigest = digest("needed-parent")
        val crowdedDigest = digest("crowded-unrelated")
        val targetHeader = header(combined = digest("target"), parent = neededDigest)
        val crowd = (0..ChdParentChainDiscovery.MAX_CANDIDATES_PER_DIGEST)
            .map { resident("crowd::$it.chd") }
        var verifications = 0
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("a::parent.chd")) + crowd },
            headerReader = { chd ->
                when {
                    chd.entryKey == target.entryKey -> parsed(targetHeader)
                    chd.entryKey == "a::parent.chd" -> parsed(header(combined = neededDigest, parent = null))
                    else -> parsed(header(combined = crowdedDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { nodes, _ ->
                verifications++
                identified(depth = nodes.size, combined = targetHeader.combinedSha1)
            },
        )

        // Nine equal claims on an unrelated digest never block the target's own chain.
        assertTrue(discovery.resolve(target, targetHeader) is ContainerIdentityResult.Identified)
        assertEquals(1, verifications)

        val crowdedTarget = resident("roms::crowded.chd")
        val crowdedHeader = header(combined = digest("crowded-target"), parent = crowdedDigest)
        val crowdedDiscovery = ChdParentChainDiscovery(
            inventory = { listOf(crowdedTarget) + crowd },
            headerReader = { chd ->
                if (chd.entryKey == crowdedTarget.entryKey) {
                    parsed(crowdedHeader)
                } else {
                    parsed(header(combined = crowdedDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("an over-budget candidate set must refuse before verification") },
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            crowdedDiscovery.resolve(crowdedTarget, crowdedHeader),
        )
    }

    @Test
    fun `branch budget refuses over-wide ancestry before verification`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val levelOne = digest("level-one")
        val levelTwo = digest("level-two")
        val targetHeader = header(combined = digest("target"), parent = levelOne)
        val discovery = ChdParentChainDiscovery(
            inventory = {
                listOf(target) +
                    (0 until 5).map { resident("mid::$it.chd") } +
                    (0 until 5).map { resident("root::$it.chd") }
            },
            headerReader = { chd ->
                when {
                    chd.entryKey == target.entryKey -> parsed(targetHeader)
                    chd.entryKey.startsWith("mid::") ->
                        parsed(header(combined = levelOne, parent = levelTwo))
                    else -> parsed(header(combined = levelTwo, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("twenty-five enumerable branches exceed the sixteen-branch budget") },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, targetHeader),
        )
    }

    @Test
    fun `depth budget refuses a chain deeper than policy`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val depth = ChdChainPayloadVerifier.MAX_CHAIN_DEPTH
        val digests = (0..depth).map { digest("ancestor-$it") }
        val targetHeader = header(combined = digest("target"), parent = digests[depth])
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target) + (1..depth).map { resident("chain::$it.chd") } },
            headerReader = { chd ->
                if (chd.entryKey == target.entryKey) {
                    parsed(targetHeader)
                } else {
                    val level = chd.entryKey.removePrefix("chain::").removeSuffix(".chd").toInt()
                    parsed(header(combined = digests[level], parent = if (level == 1) null else digests[level - 1]))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("an over-depth ancestry must refuse before verification") },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, targetHeader),
        )
    }

    @Test
    fun `over-depth decoy tail does not invalidate the valid bounded chain`() = runBlocking {
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val parentDigest = digest("shared-parent-claim")
        val targetHeader = header(combined = digest("target"), parent = parentDigest)
        val depth = ChdChainPayloadVerifier.MAX_CHAIN_DEPTH
        val tailDigests = (1..depth).map { digest("tail-$it") }
        val verified = mutableListOf<List<String>>()
        val discovery = ChdParentChainDiscovery(
            inventory = {
                listOf(target, resident("a::true-root.chd"), resident("b::deep-claim.chd")) +
                    (1..depth).map { resident("tail::$it.chd") }
            },
            headerReader = { chd ->
                when {
                    chd.entryKey == target.entryKey -> parsed(targetHeader)
                    chd.entryKey == "a::true-root.chd" ->
                        parsed(header(combined = parentDigest, parent = null))
                    chd.entryKey == "b::deep-claim.chd" ->
                        parsed(header(combined = parentDigest, parent = tailDigests[0]))
                    else -> {
                        val level = chd.entryKey.removePrefix("tail::").removeSuffix(".chd").toInt()
                        parsed(
                            header(
                                combined = tailDigests[level - 1],
                                parent = if (level == depth) null else tailDigests[level],
                            ),
                        )
                    }
                }
            },
            holderOpener = holders::open,
            verifier = { nodes, _ ->
                verified += nodes.keys()
                identified(depth = nodes.size, combined = targetHeader.combinedSha1)
            },
        )

        val result = discovery.resolve(target, targetHeader)

        // The nine-node tail can never become a policy-valid chain, so only the bounded chain counts.
        result as ContainerIdentityResult.Identified
        assertEquals(listOf(listOf("a::true-root.chd", "roms::game.chd")), verified)
    }

    @Test
    fun `malformed open at a deep node kills the pairing while the shared root survives`() = runBlocking {
        val fixture = SharedGrandparentFixture()
        val verified = mutableListOf<List<String>>()
        val discovery = fixture.discovery { nodes, _ ->
            verified += nodes.keys()
            if (nodes.keys()[1] == "mid::refuted.chd") {
                ChdChainVerification(
                    result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                    openFailureNodeIndex = 2,
                )
            } else {
                identified(depth = nodes.size, combined = fixture.targetDigest)
            }
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(2, verified.size)
        assertEquals("root::shared.chd", verified.last().first())
    }

    @Test
    fun `malformed open at the shared root prunes every path that contains it`() = runBlocking {
        val fixture = SharedGrandparentFixture()
        val verified = mutableListOf<List<String>>()
        val discovery = fixture.discovery { nodes, _ ->
            verified += nodes.keys()
            ChdChainVerification(
                result = ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                openFailureNodeIndex = 0,
            )
        }

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            discovery.resolve(fixture.target, fixture.targetHeader),
        )
        assertEquals(1, verified.size)
    }

    @Test
    fun `all paths policy-refused abstain as unsupported container`() = runBlocking {
        val fixture = SingleParentFixture(parentKeys = listOf("a::parent.chd"))
        val discovery = fixture.discovery { _, _ ->
            ChdChainVerification(
                result = ContainerIdentityResult.PolicyAbstain(
                    ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                ),
            )
        }

        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
            discovery.resolve(fixture.target, fixture.targetHeader),
        )
    }

    @Test
    fun `policy refusal stays retryable while an unreadable resident exists`() = runBlocking {
        val fixture = SingleParentFixture(
            parentKeys = listOf("a::parent.chd"),
            unreadableKeys = listOf("z::detached-volume.chd"),
        )
        val discovery = fixture.discovery { _, _ ->
            ChdChainVerification(
                result = ContainerIdentityResult.PolicyAbstain(
                    ContainerIdentityReason.UNSUPPORTED_CONTAINER,
                ),
            )
        }

        // The unread resident may hold the payload the refusal was judged without: never durable.
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE),
            discovery.resolve(fixture.target, fixture.targetHeader),
        )
    }

    @Test
    fun `malformed review at a deep node also kills only the pairing`() = runBlocking {
        val fixture = SharedGrandparentFixture()
        val verified = mutableListOf<List<String>>()
        val discovery = fixture.discovery { nodes, _ ->
            verified += nodes.keys()
            if (nodes.keys()[1] == "mid::refuted.chd") {
                ChdChainVerification(
                    result = ContainerIdentityResult.NeedsReview(ContainerIdentityReason.MALFORMED),
                    openFailureNodeIndex = 2,
                )
            } else {
                identified(depth = nodes.size, combined = fixture.targetDigest)
            }
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader)

        result as ContainerIdentityResult.Identified
        assertEquals(2, verified.size)
        assertEquals("root::shared.chd", verified.last().first())
    }

    @Test
    fun `cancellation propagates while holders close in reverse`() {
        val fixture = SingleParentFixture(parentKeys = listOf("a::parent.chd"))
        val discovery = fixture.discovery { _, _ ->
            fixture.holders.events += "native-closed"
            throw CancellationException("verification cancelled")
        }

        val failure = runCatching {
            runBlocking { discovery.resolve(fixture.target, fixture.targetHeader) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(
            listOf(
                "open:a::parent.chd",
                "open:roms::game.chd",
                "native-closed",
                "close:roms::game.chd",
                "close:a::parent.chd",
            ),
            fixture.holders.events,
        )
    }

    @Test
    fun `holder open failure closes earlier holders and stays retryable`() = runBlocking {
        val fixture = SingleParentFixture(parentKeys = listOf("a::parent.chd"))
        fixture.holders.refuse = setOf("roms::game.chd")
        var verifications = 0
        val discovery = fixture.discovery { _, _ ->
            verifications++
            error("a chain with an unopened node must never reach native verification")
        }

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE),
            discovery.resolve(fixture.target, fixture.targetHeader),
        )
        assertEquals(0, verifications)
        assertEquals(
            listOf(
                "open:a::parent.chd",
                "open-refused:roms::game.chd",
                "close:a::parent.chd",
            ),
            fixture.holders.events,
        )
    }

    @Test
    fun `one sweep reads each resident header exactly once across targets`() = runBlocking {
        val holders = HolderLog()
        val reads = mutableMapOf<String, Int>()
        val parentDigest = digest("shared-parent")
        val targetOne = resident("roms::one.chd")
        val targetTwo = resident("roms::two.chd")
        val headerOne = header(combined = digest("one"), parent = parentDigest)
        val headerTwo = header(combined = digest("two"), parent = parentDigest)
        val residents = listOf(targetOne, targetTwo, resident("a::parent.chd"))
        val discovery = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd ->
                reads.merge(chd.entryKey, 1, Int::plus)
                when (chd.entryKey) {
                    targetOne.entryKey -> parsed(headerOne)
                    targetTwo.entryKey -> parsed(headerTwo)
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { nodes, _ -> identified(depth = nodes.size, combined = nodes.keys().last()) },
        )

        assertTrue(discovery.resolve(targetOne, headerOne) is ContainerIdentityResult.Identified)
        assertTrue(discovery.resolve(targetTwo, headerTwo) is ContainerIdentityResult.Identified)
        assertEquals(residents.associate { it.entryKey to 1 }, reads)
    }

    @Test
    fun `expired slow null holder after prior context refuses without prune or mint and retries`() =
        runBlocking {
            val clock = FakeClock(0L)
            val holders = HolderLog()
            val targetDigest = digest("target")
            val parentDigest = digest("parent")
            val alternateDigest = digest("alternate-parent")
            val target = resident("roms::game.chd")
            val targetHeader = header(combined = targetDigest, parent = parentDigest)
            val parent = resident("a::parent.chd")
            val alternate = resident("b::alternate.chd")
            val residents = listOf(target, parent, alternate)
            var verifyCalls = 0
            var expireTarget = true
            val discovery = ChdParentChainDiscovery(
                inventory = { residents },
                headerReader = { chd ->
                    when (chd.entryKey) {
                        target.entryKey -> parsed(targetHeader)
                        parent.entryKey -> parsed(header(combined = parentDigest, parent = null))
                        else -> parsed(header(combined = alternateDigest, parent = null))
                    }
                },
                holderOpener = { resident ->
                    when (resident.entryKey) {
                        parent.entryKey -> holders.open(resident)
                        target.entryKey -> {
                            if (expireTarget) {
                                clock.now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                                expireTarget = false
                                null
                            } else {
                                holders.open(resident)
                            }
                        }
                        else -> holders.open(resident)
                    }
                },
                verifier = { nodes, _ ->
                    verifyCalls++
                    identified(depth = nodes.size, combined = targetDigest)
                },
                nanoTime = clock::now,
            )

            val first = discovery.resolve(
                target,
                targetHeader,
                ChdVerificationBudget.forResolve(
                    nanoTime = clock::now,
                    absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
                ),
            )
            assertEquals(
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
                first,
            )
            assertEquals(0, verifyCalls)
            assertEquals(
                listOf(
                    "open:a::parent.chd",
                    "close:a::parent.chd",
                ),
                holders.events,
            )

            clock.now = 0L
            holders.events.clear()
            val second = discovery.resolve(
                target,
                targetHeader,
                ChdVerificationBudget.forResolve(nanoTime = clock::now),
            )
            second as ContainerIdentityResult.Identified
            assertEquals(1, verifyCalls)
            assertTrue(holders.events.contains("open:roms::game.chd"))
        }

    @Test
    fun `expired slow non-null holder after opener closes immediately without prune or mint and retries`() =
        runBlocking {
            val clock = FakeClock(0L)
            val holders = HolderLog()
            val targetDigest = digest("target")
            val parentDigest = digest("parent")
            val alternateDigest = digest("alternate-parent")
            val target = resident("roms::game.chd")
            val targetHeader = header(combined = targetDigest, parent = parentDigest)
            val parent = resident("a::parent.chd")
            val alternate = resident("b::alternate.chd")
            val residents = listOf(target, parent, alternate)
            var verifyCalls = 0
            var expireTarget = true
            val discovery = ChdParentChainDiscovery(
                inventory = { residents },
                headerReader = { chd ->
                    when (chd.entryKey) {
                        target.entryKey -> parsed(targetHeader)
                        parent.entryKey -> parsed(header(combined = parentDigest, parent = null))
                        else -> parsed(header(combined = alternateDigest, parent = null))
                    }
                },
                holderOpener = { resident ->
                    when (resident.entryKey) {
                        parent.entryKey -> holders.open(resident)
                        target.entryKey -> {
                            if (expireTarget) {
                                val acquired = holders.open(resident)
                                clock.now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                                expireTarget = false
                                acquired
                            } else {
                                holders.open(resident)
                            }
                        }
                        else -> holders.open(resident)
                    }
                },
                verifier = { nodes, _ ->
                    verifyCalls++
                    identified(depth = nodes.size, combined = targetDigest)
                },
                nanoTime = clock::now,
            )

            val first = discovery.resolve(
                target,
                targetHeader,
                ChdVerificationBudget.forResolve(
                    nanoTime = clock::now,
                    absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
                ),
            )
            assertEquals(
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
                first,
            )
            assertEquals(0, verifyCalls)
            assertEquals(
                listOf(
                    "open:a::parent.chd",
                    "open:roms::game.chd",
                    "close:roms::game.chd",
                    "close:a::parent.chd",
                ),
                holders.events,
            )

            clock.now = 0L
            holders.events.clear()
            val second = discovery.resolve(
                target,
                targetHeader,
                ChdVerificationBudget.forResolve(nanoTime = clock::now),
            )
            second as ContainerIdentityResult.Identified
            assertEquals(1, verifyCalls)
            assertTrue(holders.events.contains("open:roms::game.chd"))
        }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Target plus N duplicate-equivalent root parents, with optional unreadable decoys. */
    private inner class SingleParentFixture(
        parentKeys: List<String>,
        unreadableKeys: List<String> = emptyList(),
    ) {
        val holders = HolderLog()
        val targetDigest = digest("target")
        private val parentDigest = digest("parent")
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = targetDigest, parent = parentDigest)
        private val residents =
            listOf(target) + parentKeys.map(::resident) + unreadableKeys.map(::resident)
        private val headers = buildMap {
            put(target.entryKey, parsed(targetHeader))
            parentKeys.forEach { put(it, parsed(header(combined = parentDigest, parent = null))) }
            unreadableKeys.forEach { put(it, ChdResidentHeaderRead.Unreadable) }
        }

        fun discovery(
            verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification,
        ) = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd -> headers.getValue(chd.entryKey) },
            holderOpener = holders::open,
            verifier = verifier,
        )
    }

    /** One digest claimed by both a true root and a differential node with its own verifiable root. */
    private inner class DivergentFixture {
        val holders = HolderLog()
        val targetDigest = digest("target")
        private val sharedDigest = digest("shared-parent-digest")
        private val grandDigest = digest("grandparent")
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = targetDigest, parent = sharedDigest)
        private val residents = listOf(
            target,
            resident("a::standalone-claim.chd"),
            resident("b::differential-claim.chd"),
            resident("c::grandparent.chd"),
        )
        private val headers = mapOf(
            target.entryKey to parsed(targetHeader),
            "a::standalone-claim.chd" to parsed(header(combined = sharedDigest, parent = null)),
            "b::differential-claim.chd" to parsed(header(combined = sharedDigest, parent = grandDigest)),
            "c::grandparent.chd" to parsed(header(combined = grandDigest, parent = null)),
        )

        fun discovery(
            verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification,
        ) = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd -> headers.getValue(chd.entryKey) },
            holderOpener = holders::open,
            verifier = verifier,
        )
    }

    /** Two mid-level duplicate claims above one shared grandparent root. */
    private inner class SharedGrandparentFixture {
        val holders = HolderLog()
        val targetDigest = digest("target")
        private val midDigest = digest("mid")
        private val rootDigest = digest("root")
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = targetDigest, parent = midDigest)
        private val residents = listOf(
            target,
            resident("mid::refuted.chd"),
            resident("mid::verified.chd"),
            resident("root::shared.chd"),
        )
        private val headers = mapOf(
            target.entryKey to parsed(targetHeader),
            "mid::refuted.chd" to parsed(header(combined = midDigest, parent = rootDigest)),
            "mid::verified.chd" to parsed(header(combined = midDigest, parent = rootDigest)),
            "root::shared.chd" to parsed(header(combined = rootDigest, parent = null)),
        )

        fun discovery(
            verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification,
        ) = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd -> headers.getValue(chd.entryKey) },
            holderOpener = holders::open,
            verifier = verifier,
        )
    }

    private class HolderLog {
        val events = mutableListOf<String>()
        var refuse: Set<String> = emptySet()

        fun open(resident: ChdChainResident): NativeSeekablePath? {
            if (resident.entryKey in refuse) {
                events += "open-refused:${resident.entryKey}"
                return null
            }
            events += "open:${resident.entryKey}"
            return object : NativeSeekablePath {
                override val path = "native:${resident.entryKey}"
                override fun close() {
                    events += "close:${resident.entryKey}"
                }
            }
        }
    }

    /** Replays scripted per-node payloads and logs native close ordering into the shared holder log. */
    private class PayloadChainSession(
        declared: List<ChdVerificationNode>,
        private val payloads: List<ByteArray>,
        private val holders: HolderLog,
    ) : ChdChainReadSession {
        override val nodes: List<ChdChainNodeInfo> = declared.map { node ->
            ChdChainNodeInfo(
                version = node.header.version,
                logicalBytes = node.header.logicalBytes,
                hunkBytes = node.header.hunkBytes,
                hunkCount = (
                    (node.header.logicalBytes + node.header.hunkBytes - 1L) / node.header.hunkBytes
                    ).toInt(),
                rawSha1 = node.header.rawSha1,
                combinedSha1 = node.header.combinedSha1,
                parentSha1 = node.header.parentSha1,
                compressionTags = node.header.compressionTags,
            )
        }

        override fun readHunk(nodeIndex: Int, hunkIndex: Int, destination: ByteArray): ChdHunkRead {
            destination.fill(0)
            val payload = payloads[nodeIndex]
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
            holders.events += "native-closed"
        }
    }

    private fun resident(entryKey: String) = ChdChainResident(
        entryKey = entryKey,
        sourceRootUri = entryKey.substringBefore("::"),
        relativePath = entryKey.substringAfter("::"),
        documentUri = null,
        managedPath = "/roms/${entryKey.substringAfterLast("::")}",
    )

    private fun physicalResident(root: String, relativePath: String) = ChdChainResident(
        entryKey = physicalResidentKey(root, relativePath),
        sourceRootUri = root,
        relativePath = relativePath,
        documentUri = null,
        managedPath = "/roms/$relativePath",
    )

    private fun parsed(header: ChdHeaderEvidence) = ChdResidentHeaderRead.Parsed(header)

    private fun header(
        combined: String,
        parent: String?,
        logicalBytes: Long = 8L,
    ) = ChdHeaderEvidence(
        version = 3,
        logicalBytes = logicalBytes,
        hunkBytes = 4,
        rawSha1 = combined,
        combinedSha1 = combined,
        parentSha1 = parent,
        compressionTags = listOf(0, 0, 0, 0),
    )

    private fun identified(depth: Int, combined: String) = ChdChainVerification(
        result = ContainerIdentityResult.Identified(
            VerifiedChdIdentity(
                version = 3,
                logicalBytes = 8L,
                rawSha1 = combined,
                combinedSha1 = combined,
                chainDepth = depth,
                compressionTags = listOf(0, 0, 0, 0),
            ),
        ),
    )

    private fun List<ChdVerificationNode>.keys(): List<String> =
        map { it.path.removePrefix("native:") }

    private fun digest(label: String): String = sha1(label.toByteArray())

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
