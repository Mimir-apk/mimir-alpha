package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChdVerificationBudgetTest {

    @Test
    fun `default resolve budget pins six hundred seconds`() {
        val clock = FakeClock(1_000L)
        val budget = ChdVerificationBudget.forResolve(nanoTime = clock::now)

        assertEquals(
            ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS,
            budget.remainingNanos(),
        )
    }

    @Test
    fun `caller absolute deadline clamps aggregate budget`() {
        val clock = FakeClock(100L)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = 200L,
        )

        assertEquals(100L, budget.remainingNanos())
    }

    @Test
    fun `later caller absolute deadline never extends beyond default aggregate`() {
        val clock = FakeClock(0L)
        val delta = 60L * 1_000_000_000L
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS + delta,
        )

        assertEquals(ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS, budget.remainingNanos())
    }

    @Test
    fun `earlier caller absolute deadline still clamps below aggregate`() {
        val clock = FakeClock(0L)
        val earlier = 30L * 1_000_000_000L
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = earlier,
        )

        assertEquals(earlier, budget.remainingNanos())
        assertTrue(budget.remainingNanos() < ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS)
    }

    @Test
    fun `saturating deadline arithmetic never yields negative or refreshed allowance`() {
        val nearOverflow = Long.MAX_VALUE - 1_000L
        val clock = FakeClock(nearOverflow)
        val budget = ChdVerificationBudget.forResolve(nanoTime = clock::now)

        assertTrue(budget.remainingNanos() >= 0L)
        assertEquals(1_000L, budget.remainingNanos())

        clock.now = Long.MAX_VALUE
        assertEquals(0L, budget.remainingNanos())
        assertTrue(budget.isExhausted())

        val clamped = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = nearOverflow + 500L,
        )
        assertEquals(0L, clamped.remainingNanos())
        assertTrue(clamped.isExhausted())
    }

    @Test
    fun `overflow safe remaining never revives an already expired extreme deadline`() {
        val clock = FakeClock(Long.MAX_VALUE)
        val expired = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = 1L,
        )
        assertEquals(0L, expired.remainingNanos())
        assertTrue(expired.isExhausted())

        clock.now = Long.MIN_VALUE
        val wrapped = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = 100L,
        )
        assertEquals(0L, wrapped.remainingNanos())
        assertTrue(wrapped.isExhausted())
    }

    @Test
    fun `saturating aggregate never extends beyond default window`() {
        val clock = FakeClock(Long.MAX_VALUE - 50L)
        val budget = ChdVerificationBudget.forResolve(nanoTime = clock::now)
        assertTrue(budget.remainingNanos() <= 50L)
        assertFalse(budget.remainingNanos() > ChdVerificationBudget.DEFAULT_AGGREGATE_NANOS)
    }

    @Test
    fun `five second admission floor blocks holder opens`() = runBlocking {
        val clock = FakeClock(0L)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS - 1,
        )
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val parentDigest = digest("parent")
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("a::parent.chd")) },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey ->
                        parsed(header(combined = digest("target"), parent = parentDigest))
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("budget must refuse before verification") },
            nanoTime = clock::now,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, header(combined = digest("target"), parent = parentDigest), budget),
        )
        assertTrue(holders.events.isEmpty())
    }

    @Test
    fun `enumeration refuses retryably when budget expires during descent`() = runBlocking {
        var nanoCalls = 0
        val nanoTime = {
            nanoCalls++
            if (nanoCalls >= 8) ChdVerificationBudget.MIN_ADMISSION_NANOS + 2 else 0L
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = nanoTime,
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )
        val holders = HolderLog()
        var verifyCalls = 0
        val target = resident("roms::game.chd")
        val parentDigest = digest("parent")
        val targetHeader = header(combined = digest("target"), parent = parentDigest)
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("a::parent.chd")) },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ ->
                verifyCalls++
                error("enumeration must refuse before verification")
            },
            nanoTime = nanoTime,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, targetHeader, budget),
        )
        assertTrue(holders.events.isEmpty())
        assertEquals(0, verifyCalls)
    }

    @Test
    fun `aggregate branch budget is shared across catalog enumeration and verification`() = runBlocking {
        val clock = FakeClock(0L)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )
        val holders = HolderLog()
        val target = resident("roms::game.chd")
        val parentDigest = digest("parent")
        val targetHeader = header(combined = digest("target"), parent = parentDigest)
        var catalogReads = 0
        val discovery = ChdParentChainDiscovery(
            inventory = { listOf(target, resident("a::parent.chd")) },
            headerReader = { chd ->
                catalogReads++
                clock.now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { _, _ -> error("enumeration must exhaust budget before verification") },
            nanoTime = clock::now,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(target, targetHeader, budget),
        )
        assertTrue(catalogReads >= 1)
        assertTrue(holders.events.isEmpty())
    }

    @Test
    fun `decreasing attempt budgets pass strictly smaller slices to each verifier branch`() = runBlocking {
        val clock = FakeClock(0L)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = 20L * 1_000_000_000L,
        )
        val seen = mutableListOf<Long>()
        val fixture = DivergentBudgetFixture()
        val discovery = fixture.discovery { _, attemptBudget ->
            seen += attemptBudget.remainingNanos()
            clock.now += 5L * 1_000_000_000L
            identified(depth = 2, combined = fixture.targetDigest)
        }

        discovery.resolve(fixture.target, fixture.targetHeader, budget)

        assertTrue(seen.size >= 2)
        assertTrue(seen[0] > seen.last())
    }

    @Test
    fun `partial verified structure cannot identify when final alternate expires mid read`() = runBlocking {
        val fixture = DivergentBudgetFixture()
        val clock = FakeClock(0L)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = clock::now,
            absoluteDeadlineNanos = 30L * 1_000_000_000L,
        )
        var verifyCalls = 0
        val discovery = fixture.discovery { nodes, attemptBudget ->
            verifyCalls++
            ChdChainPayloadVerifier.verifyChain(
                nodes,
                backend = ChdChainVerifierBackend {
                    ChdChainOpen.Opened(
                        if (verifyCalls == 1) {
                            fixture.successSession(nodes)
                        } else {
                            fixture.expiringSession(nodes, clock, attemptBudget)
                        },
                    )
                },
                budget = attemptBudget,
            )
        }

        val result = discovery.resolve(fixture.target, fixture.targetHeader, budget)

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(verifyCalls >= 2)
    }

    @Test
    fun `two verified structures keep ambiguity precedence over budget refusal`() = runBlocking {
        val fixture = DivergentBudgetFixture()
        val budget = ChdVerificationBudget.forResolve()
        val discovery = fixture.discovery { nodes, _ ->
            identified(depth = nodes.size, combined = fixture.targetDigest)
        }

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS),
            discovery.resolve(fixture.target, fixture.targetHeader, budget),
        )
    }

    @Test
    fun `budget exhaustion does not prune and the next resolve retries`() = runBlocking {
        val fixture = SingleBudgetFixture()
        var attempts = 0
        val clock = FakeClock(0L)
        val discovery = fixture.discovery { _, attemptBudget ->
            attempts++
            if (attempts == 1) {
                clock.now = attemptBudget.remainingNanos()
                ChdChainVerification(
                    result = ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    ),
                    openFailureNodeIndex = 0,
                    budgetExhausted = true,
                )
            } else {
                identified(depth = 2, combined = fixture.targetDigest)
            }
        }

        val first = discovery.resolve(
            fixture.target,
            fixture.targetHeader,
            ChdVerificationBudget.forResolve(
                nanoTime = clock::now,
                absoluteDeadlineNanos = 20L * 1_000_000_000L,
            ),
        )
        clock.now = 0L
        val second = discovery.resolve(
            fixture.target,
            fixture.targetHeader,
            ChdVerificationBudget.forResolve(nanoTime = clock::now),
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            first,
        )
        second as ContainerIdentityResult.Identified
        assertEquals(2, attempts)
    }

    @Test
    fun `budget refusal still closes holders in reverse order`() = runBlocking {
        val fixture = SingleBudgetFixture()
        val clock = FakeClock(0L)
        val discovery = fixture.discovery { _, attemptBudget ->
            clock.now = attemptBudget.remainingNanos()
            ChdChainVerification(
                result = ContainerIdentityResult.RetryableFailure(
                    ContainerIdentityReason.RESOURCE_REFUSED,
                ),
                budgetExhausted = true,
            )
        }

        discovery.resolve(
            fixture.target,
            fixture.targetHeader,
            ChdVerificationBudget.forResolve(
                nanoTime = clock::now,
                absoluteDeadlineNanos = 20L * 1_000_000_000L,
            ),
        )

        assertEquals(
            listOf(
                "open:a::parent.chd",
                "open:roms::game.chd",
                "close:roms::game.chd",
                "close:a::parent.chd",
            ),
            fixture.holders.events,
        )
    }

    @Test
    fun `partial catalog build is never cached across resolves`() = runBlocking {
        val clock = FakeClock(0L)
        val target = resident("roms::game.chd")
        val parentDigest = digest("parent")
        val targetHeader = header(combined = digest("target"), parent = parentDigest)
        var inventoryCalls = 0
        var headerReads = 0
        val holders = HolderLog()
        val discovery = ChdParentChainDiscovery(
            inventory = {
                inventoryCalls++
                listOf(target, resident("a::parent.chd"), resident("b::other.chd"))
            },
            headerReader = { chd ->
                headerReads++
                if (headerReads == 3) {
                    clock.now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                }
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    "b::other.chd" -> parsed(header(combined = digest("other"), parent = null))
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = { nodes, _ -> identified(depth = nodes.size, combined = targetHeader.combinedSha1) },
            nanoTime = clock::now,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            discovery.resolve(
                target,
                targetHeader,
                ChdVerificationBudget.forResolve(
                    nanoTime = clock::now,
                    absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
                ),
            ),
        )
        assertEquals(1, inventoryCalls)
        assertEquals(3, headerReads)

        clock.now = 0L
        val retry = discovery.resolve(
            target,
            targetHeader,
            ChdVerificationBudget.forResolve(nanoTime = clock::now),
        )
        retry as ContainerIdentityResult.Identified

        assertEquals(2, inventoryCalls)
        assertTrue(holders.events.isNotEmpty())
    }

    @Test
    fun `cancellation propagates through budget-aware discovery`() {
        val fixture = SingleBudgetFixture()
        val discovery = fixture.discovery { _, _ ->
            fixture.holders.events += "native-closed"
            throw CancellationException("budget cancellation")
        }

        val failure = runCatching {
            runBlocking {
                discovery.resolve(fixture.target, fixture.targetHeader)
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(fixture.holders.events.contains("close:roms::game.chd"))
    }

    @Test
    fun `standalone verifier honors injected smaller budget`() = runBlocking {
        val payload = ByteArray(8)
        val raw = sha1(payload)
        var now = 0L
        val timed = object : ChdPayloadVerifierTestSupport.FakeSession(payload, raw) {
            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return super.readHunk(index, destination)
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow.chd",
            ChdPayloadVerifierTestSupport.header(payload.size.toLong(), raw, timed.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(timed) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(timed.closed)
    }

    @Test
    fun `chain verifier honors injected smaller budget`() = runBlocking {
        var now = 0L
        val root = budgetNode("/root.chd", "root".toByteArray(), null)
        val session = object : ChdChainPayloadVerifierTestSupport.FakeChainSession(listOf(root)) {
            override fun readHunk(
                nodeIndex: Int,
                hunkIndex: Int,
                destination: ByteArray,
            ): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return super.readHunk(nodeIndex, hunkIndex, destination)
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdChainPayloadVerifier.verify(
            chain = listOf(root),
            backend = ChdChainVerifierBackend { ChdChainOpen.Opened(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `enricher page admission stops before deadline with exhausted false`() = runBlocking {
        val clock = FakeClock(0L)
        val row = budgetGame(entryKey = "root::a", sizeBytes = 1L)
        var fetches = 0
        val dao = budgetDao(
            unresolved = {
                fetches++
                if (fetches == 1) listOf(row) else listOf(row, budgetGame(entryKey = "root::b", sizeBytes = 2L))
            },
        )
        val enricher = budgetEnricher(dao, clock)

        val first = enricher.enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
            absoluteDeadlineNanos = 50L,
        )
        clock.now = 100L
        val second = enricher.enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
            afterCursor = first.nextCursor,
            absoluteDeadlineNanos = 100L,
        )

        assertEquals(1, first.attempted)
        assertFalse(first.exhausted)
        assertEquals(0, second.attempted)
        assertFalse(second.exhausted)
    }

    @Test
    fun `checkpoint fires before batch resolve`() = runBlocking {
        val row = budgetGame(entryKey = "slow.chd", sizeBytes = 1L, platform = "ps1")
        val checkpoints = mutableListOf<LibraryIdentityEnricher.IdentityEnrichmentCursor>()
        var resolveStarted = false
        val dao = budgetDao(unresolved = { listOf(row) })
        val enricher = budgetEnricher(
            dao = dao,
            clock = FakeClock(0L),
            opener = object : ContentOpener {
                override fun open(documentUri: String?, managedPath: String?): java.io.InputStream? = null

                override fun openRandomAccess(
                    documentUri: String?,
                    managedPath: String?,
                ): RandomAccessContent {
                    resolveStarted = true
                    return ByteArrayRandomAccess(ByteArray(132))
                }
            },
        )

        enricher.enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
            onCheckpoint = checkpoints::add,
        )

        assertEquals(listOf(LibraryIdentityEnricher.IdentityEnrichmentCursor(1L, "slow.chd")), checkpoints)
        assertTrue(resolveStarted)
    }

    @Test
    fun `concurrent enricher page gives each game an independent resolve budget`() = runBlocking {
        val fixture = BudgetDifferentialFixture()
        val secondChild = fixture.child.copy(
            entryKey = "root::child-b.chd",
            gameId = "root::child-b.chd-game",
            sizeBytes = 2L,
            managedPath = "/roms/child-b.chd",
        )
        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): java.io.InputStream? =
                fixture.opener.open(documentUri, managedPath)

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = when (requireNotNull(managedPath)) {
                "/roms/child-b.chd" -> ByteArrayRandomAccess(
                    budgetChdHeader(
                        ByteArray(20) { (it + 81).toByte() },
                        ByteArray(20) { (it + 121).toByte() },
                        ByteArray(20) { (it + 41).toByte() },
                    ),
                )
                else -> fixture.opener.openRandomAccess(documentUri, managedPath)
                    ?: error("missing header for $managedPath")
            }

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath? = fixture.opener.openNativeSeekablePath(documentUri, managedPath)
        }
        val clock = FakeClock(0L)
        val workerDeadline = 90L * 1_000_000_000L
        val initialAllowances = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val budgetIdentities = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" -> listOf(fixture.child, secondChild)
                "availableChdShapedResidents" -> listOf(fixture.child, secondChild, fixture.parent)
                    .sortedBy(GameEntity::entryKey)
                "setIdentities" -> Unit
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
        val enricher = budgetEnricher(dao, clock, opener).apply {
            chainVerifier = { _, budget ->
                budgetIdentities += System.identityHashCode(budget)
                initialAllowances += budget.remainingNanos()
                clock.now += 60L * 1_000_000_000L
                identified(depth = 2, combined = fixture.childDigest)
            }
        }

        enricher.enrich(
            maxGames = 2,
            batch = 2,
            concurrency = 2,
            reconcile = false,
            absoluteDeadlineNanos = workerDeadline,
        )

        assertEquals(2, budgetIdentities.toSet().size)
        assertEquals(workerDeadline, initialAllowances.maxOrNull())
        assertTrue(initialAllowances.minOrNull()!! >= workerDeadline - 60L * 1_000_000_000L)
    }

    @Test
    fun `retryable budget refusal writes no identity`() = runBlocking {
        val fixture = BudgetDifferentialFixture()
        val enricher = budgetEnricher(fixture.dao, FakeClock(0L), fixture.opener).apply {
            chainVerifier = { _, _ ->
                ChdChainVerification(
                    result = ContainerIdentityResult.RetryableFailure(
                        ContainerIdentityReason.RESOURCE_REFUSED,
                    ),
                    budgetExhausted = true,
                )
            }
        }

        val report = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)

        assertEquals(1, report.retryable)
        assertNull(fixture.write)
    }

    private fun budgetEnricher(
        dao: GameDao,
        clock: FakeClock,
        opener: ContentOpener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): java.io.InputStream? = null
        },
    ) = LibraryIdentityEnricher(
        chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
        dao = dao,
        datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
        idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
        opener = opener,
        nanoTime = clock::now,
    )

    private fun budgetDao(
        unresolved: () -> List<GameEntity>,
        chdResidents: () -> List<GameEntity> = { emptyList() },
    ): GameDao = Proxy.newProxyInstance(
        GameDao::class.java.classLoader,
        arrayOf(GameDao::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "unresolvedPrimaries", "unresolvedPrimariesAfter" -> unresolved()
            "availableChdShapedResidents" -> chdResidents()
            "setIdentities" -> Unit
            else -> error("Unexpected GameDao call: ${method.name}")
        }
    } as GameDao

    private fun budgetGame(
        entryKey: String,
        sizeBytes: Long,
        platform: String = "gba",
    ) = GameEntity(
        entryKey = entryKey,
        gameId = "$entryKey-game",
        platformId = platform,
        contentKind = "GAME",
        displayName = entryKey,
        title = "Before",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = sizeBytes,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/roms/$entryKey",
        relativePath = entryKey,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private inner class BudgetDifferentialFixture {
        private val parentRaw = ByteArray(20) { (it + 1).toByte() }
        private val parentCombined = ByteArray(20) { (it + 41).toByte() }
        private val childRaw = ByteArray(20) { (it + 81).toByte() }
        private val childCombined = ByteArray(20) { (it + 121).toByte() }
        val childDigest = childCombined.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val child = budgetGame("child.chd", 100L, "ps1")
        val parent = budgetGame("renamed-parent.chd", 100L, "ps1")
            .copy(entryKey = "root::renamed-parent.chd", isPrimaryMember = false)
        var write: IdentityWrite? = null
        private var resolved = false

        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(child)
                "availableChdShapedResidents" -> listOf(child, parent).sortedBy(GameEntity::entryKey)
                "setIdentities" -> {
                    @Suppress("UNCHECKED_CAST")
                    write = (requireNotNull(args)[0] as List<IdentityWrite>).single()
                    resolved = true
                    Unit
                }
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao

        private val headersByPath = mapOf(
            "/roms/child.chd" to budgetChdHeader(childRaw, childCombined, parentCombined),
            "/roms/renamed-parent.chd" to budgetChdHeader(parentRaw, parentCombined, null),
        )

        val opener = object : ContentOpener {
            override fun open(documentUri: String?, managedPath: String?): java.io.InputStream? =
                error("CHD chain authority must use random and native seek seams")

            override fun openRandomAccess(
                documentUri: String?,
                managedPath: String?,
            ): RandomAccessContent = ByteArrayRandomAccess(headersByPath.getValue(requireNotNull(managedPath)))

            override fun openNativeSeekablePath(
                documentUri: String?,
                managedPath: String?,
            ): NativeSeekablePath = object : NativeSeekablePath {
                override val path = "native:${requireNotNull(managedPath)}"
                override fun close() = Unit
            }
        }
    }

    private fun budgetChdHeader(
        raw: ByteArray,
        combined: ByteArray,
        parent: ByteArray?,
    ): ByteArray = ByteArray(132).also { bytes ->
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes.putBe32(8, 124)
        bytes.putBe32(12, 5)
        bytes.putBe64(32, 4096L)
        bytes.putBe32(56, 4096)
        bytes.putBe32(60, 512)
        raw.copyInto(bytes, 64)
        combined.copyInto(bytes, 84)
        parent?.copyInto(bytes, 104)
    }

    private class ByteArrayRandomAccess(
        private val bytes: ByteArray,
    ) : RandomAccessContent {
        override val length = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset !in 0 until length) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            return count
        }

        override fun close() = Unit
    }

    private fun ByteArray.putBe32(at: Int, value: Int) {
        repeat(4) { index -> this[at + index] = (value ushr ((3 - index) * 8)).toByte() }
    }

    private fun ByteArray.putBe64(at: Int, value: Long) {
        repeat(8) { index -> this[at + index] = (value ushr ((7 - index) * 8)).toByte() }
    }

    private class HolderLog {
        val events = mutableListOf<String>()

        fun open(resident: ChdChainResident): NativeSeekablePath? {
            events += "open:${resident.entryKey}"
            return object : NativeSeekablePath {
                override val path = "native:${resident.entryKey}"
                override fun close() {
                    events += "close:${resident.entryKey}"
                }
            }
        }
    }

    private inner class SingleBudgetFixture {
        val holders = HolderLog()
        val targetDigest = digest("target")
        private val parentDigest = digest("parent")
        val target = resident("roms::game.chd")
        val targetHeader = header(combined = targetDigest, parent = parentDigest)
        private val residents = listOf(target, resident("a::parent.chd"))

        fun discovery(
            verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification,
        ) = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    else -> parsed(header(combined = parentDigest, parent = null))
                }
            },
            holderOpener = holders::open,
            verifier = verifier,
        )
    }

    private inner class DivergentBudgetFixture {
        private val targetPayload = "target-payload".toByteArray()
        val targetDigest = sha1(targetPayload)
        private val sharedPayload = "shared-parent".toByteArray()
        private val sharedDigest = sha1(sharedPayload)
        private val grandPayload = "grandparent".toByteArray()
        private val grandDigest = sha1(grandPayload)
        val target = resident("roms::game.chd")
        val targetHeader = payloadHeader(targetPayload, sharedDigest)
        private val residents = listOf(
            target,
            resident("a::standalone-claim.chd"),
            resident("b::differential-claim.chd"),
            resident("c::grandparent.chd"),
        )
        private val payloadsByPath = mapOf(
            "/roms/game.chd" to targetPayload,
            "/roms/standalone-claim.chd" to sharedPayload,
            "/roms/differential-claim.chd" to sharedPayload,
            "/roms/grandparent.chd" to grandPayload,
        )

        fun discovery(
            verifier: suspend (List<ChdVerificationNode>, ChdVerificationBudget) -> ChdChainVerification,
        ) = ChdParentChainDiscovery(
            inventory = { residents },
            headerReader = { chd ->
                when (chd.entryKey) {
                    target.entryKey -> parsed(targetHeader)
                    "a::standalone-claim.chd" -> parsed(payloadHeader(sharedPayload, parent = null))
                    "b::differential-claim.chd" -> parsed(payloadHeader(sharedPayload, grandDigest))
                    else -> parsed(payloadHeader(grandPayload, parent = null))
                }
            },
            holderOpener = { resident ->
                object : NativeSeekablePath {
                    override val path = requireNotNull(resident.managedPath)
                    override fun close() = Unit
                }
            },
            verifier = verifier,
        )

        fun successSession(nodes: List<ChdVerificationNode>): ChdChainReadSession =
            DivergentChainSession(nodes, payloadsByPath)

        fun expiringSession(
            nodes: List<ChdVerificationNode>,
            clock: FakeClock,
            budget: ChdVerificationBudget,
        ): ChdChainReadSession {
            val expireAt = clock.now + budget.remainingNanos()
            return DivergentChainSession(nodes, payloadsByPath) { _, _ ->
                clock.now = expireAt + 1
            }
        }
    }

    private class DivergentChainSession(
        private val declared: List<ChdVerificationNode>,
        private val payloadsByPath: Map<String, ByteArray>,
        private val onReadHunk: ((Int, Int) -> Unit)? = null,
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
        var closed = false
            private set

        override fun readHunk(
            nodeIndex: Int,
            hunkIndex: Int,
            destination: ByteArray,
        ): ChdHunkRead {
            onReadHunk?.invoke(nodeIndex, hunkIndex)
            destination.fill(0)
            val payload = payloadsByPath[declared[nodeIndex].path] ?: return ChdHunkRead.MALFORMED
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
        }
    }

    private fun payloadHeader(payload: ByteArray, parent: String?) = ChdHeaderEvidence(
        version = 3,
        logicalBytes = payload.size.toLong(),
        hunkBytes = 4,
        rawSha1 = sha1(payload),
        combinedSha1 = sha1(payload),
        parentSha1 = parent,
        compressionTags = listOf(0, 0, 0, 0),
    )

    private fun resident(entryKey: String) = ChdChainResident(
        entryKey = entryKey,
        sourceRootUri = entryKey.substringBefore("::"),
        relativePath = entryKey.substringAfter("::"),
        documentUri = null,
        managedPath = "/roms/${entryKey.substringAfterLast("::")}",
    )

    private fun parsed(header: ChdHeaderEvidence) = ChdResidentHeaderRead.Parsed(header)

    private fun header(combined: String, parent: String?) = ChdHeaderEvidence(
        version = 3,
        logicalBytes = 8L,
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

    private fun digest(label: String): String = sha1(label.toByteArray())

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun budgetNode(path: String, payload: ByteArray, parentSha1: String?) =
        ChdVerificationNode(
            path = path,
            header = header(sha1(payload), parentSha1),
        )
}

internal class FakeClock(var now: Long = 0L)

internal object ChdPayloadVerifierTestSupport {
    fun header(logicalBytes: Long, rawSha1: String, combinedSha1: String) = ChdHeaderEvidence(
        version = 5,
        logicalBytes = logicalBytes,
        hunkBytes = 4,
        rawSha1 = rawSha1,
        combinedSha1 = combinedSha1,
        parentSha1 = null,
    )

    open class FakeSession(
        private val payload: ByteArray,
        override val rawSha1: String,
    ) : ChdReadSession {
        override val version = 5
        override val logicalBytes = payload.size.toLong()
        override val hunkBytes = 4
        override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
        override val combinedSha1 = rawSha1
        var closed = false
            private set

        override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
            destination.fill(0)
            val start = index * hunkBytes
            if (start >= payload.size) return ChdHunkRead.MALFORMED
            payload.copyInto(destination, 0, start, minOf(payload.size, start + hunkBytes))
            return ChdHunkRead.OK
        }

        override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead = ChdMetadataRead.End

        override fun close() {
            closed = true
        }
    }
}

internal object ChdChainPayloadVerifierTestSupport {
    open class FakeChainSession(
        private val declared: List<ChdVerificationNode>,
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
        var closed = false
            private set

        override fun readHunk(
            nodeIndex: Int,
            hunkIndex: Int,
            destination: ByteArray,
        ): ChdHunkRead {
            destination.fill(0)
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
}
