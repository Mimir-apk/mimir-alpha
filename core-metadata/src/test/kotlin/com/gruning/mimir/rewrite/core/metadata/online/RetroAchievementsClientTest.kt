package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public boundary. `app` cannot see anything `internal` in this module, so what this class exposes IS the
 * RA feature as far as the rest of the codebase is concerned — and equally, what it does NOT expose is the
 * safety story: URL building that carries the user's key, the gate, the host allowlist.
 *
 * These tests pin both halves: the door works, and it is still the only door.
 */
class RetroAchievementsClientTest {

    private val rom = ByteArray(2048) { (it % 61).toByte() }
    private val romMd5 = MessageDigest.getInstance("MD5").digest(rom).joinToString("") { "%02x".format(it) }

    private fun client(
        gateOpen: Boolean = true,
        account: RetroAchievementsAccount? = RetroAchievementsAccount("player", "key"),
        calls: MutableList<String> = mutableListOf(),
        respond: (String) -> RaHttpResult = { RaHttpResult.Body(body(it)) },
        hashLibraryGet: suspend () -> RaHashLibraryResult = { RaHashLibraryResult.Unavailable },
        onCredentialsRejected: (String?) -> Unit = {},
    ) = RetroAchievementsClient(
        RetroAchievementsSource(
            gateOpen = { gateOpen },
            auth = {
                account?.let {
                    RetroAchievementsApi.Auth(
                        username = it.username,
                        apiKey = it.apiKey,
                        credentialRevision = it.credentialRevision,
                    )
                }
            },
            httpGet = { url -> calls += url; respond(url) },
            hashLibraryGet = hashLibraryGet,
        ),
        onCredentialsRejected,
    )

    private fun body(url: String): String = when {
        "API_GetConsoleIDs" in url -> """[{"ID":1,"Name":"Mega Drive"}]"""
        "API_GetGameList" in url ->
            """[{"ID":77,"Title":"Sonic","ConsoleID":1,"NumAchievements":3,"Hashes":["$romMd5"]}]"""
        "API_GetGameInfoAndUserProgress" in url -> """
            {"ID":77,"Title":"Sonic the Hedgehog","NumAchievements":3,
             "NumAwardedToUser":2,"NumAwardedToUserHardcore":1,"Achievements":{
              "1":{"ID":1,"Title":"Ring King","Description":"Collect 100 rings","Points":5,
                   "DateEarned":"2026-01-02 03:04:05"},
              "2":{"ID":2,"Title":"Speed Demon","Description":"Finish fast","Points":10},
              "3":{"ID":3,"Title":"Perfectionist","Description":"No damage","Points":25,
                   "DateEarnedHardcore":"2026-02-02 00:00:00"}
            }}
        """.trimIndent()
        "API_GetUserProfile" in url -> """{"User":"player","ULID":"01JPLAYER000000000000000000"}"""
        else -> "[]"
    }

    private fun hashLibrary(vararg hashes: String): RaHashLibraryResult.Loaded {
        val entries = hashes.joinToString(",") { "\"$it\":1" }
        val packed = parsePackedHashLibrary(StringReader("""{"Success":true,"MD5List":{$entries}}"""))!!
        return RaHashLibraryResult.Loaded(packed)
    }

    private suspend fun RetroAchievementsClient.resolveRom() = resolveGameId(
        platformId = "genesis",
        fileName = "sonic.md",
        sizeBytes = rom.size.toLong(),
        openStream = { ByteArrayInputStream(rom) },
    )

    private suspend fun RetroAchievementsClient.matchRom(
        platformId: String = "genesis",
        bytes: ByteArray = rom,
    ) = match(
        platformId = platformId,
        fileName = "sonic.md",
        sizeBytes = bytes.size.toLong(),
        openStream = { ByteArrayInputStream(bytes) },
    )

    // ---- the door works ----------------------------------------------------

    /** The end-to-end shape `app` will call: hash a local file, get an id, read this user's progress. */
    @Test
    fun resolvesAnIdAndReadsProgressThroughThePublicApi() = runBlocking {
        val client = client()
        assertEquals(77, client.resolveRom())

        val outcome = client.progressFor(77)
        assertTrue("progress must come back through the facade", outcome is RaProgressOutcome.Read)
        val progress = (outcome as RaProgressOutcome.Read).progress
        assertEquals(77, progress.gameId)
        assertEquals("Sonic the Hedgehog", progress.title)
        assertEquals(3, progress.achievements.size)

        val ringKing = progress.achievements.single { it.id == 1 }
        assertEquals("Ring King", ringKing.title)
        assertEquals("Collect 100 rings", ringKing.description)
        assertEquals(5, ringKing.points)
        assertTrue(ringKing.unlocked)
        assertFalse("DateEarned alone is not the hardcore tier", ringKing.unlockedHardcore)
        assertTrue(progress.achievements.single { it.id == 3 }.unlockedHardcore)
        assertFalse("no date at all is locked", progress.achievements.single { it.id == 2 }.unlocked)
    }

    /**
     * REGRESSION GUARD: the totals are derived from the rows, so a card can never render "3 of 12" over a
     * list of 4. This is why [RetroAchievementsProgress] recomputes rather than carrying parallel counters.
     */
    @Test
    fun countsAreDerivedFromTheRowsAndCannotDisagreeWithThem() = runBlocking {
        val progress = (client().progressFor(77) as RaProgressOutcome.Read).progress
        assertEquals(3, progress.totalCount)
        assertEquals("only #1 and #3 are earned", 2, progress.unlockedCount)
        assertEquals(30, progress.earnedPoints)
        assertEquals(40, progress.totalPoints)
        assertEquals(progress.achievements.size, progress.totalCount)
        assertEquals(progress.achievements.count { it.unlocked }, progress.unlockedCount)
    }

    // ---- the three-state contract ------------------------------------------

    /** A hash that a set carries is [RetroAchievementsMatch.Matched], carrying the id progress needs. */
    @Test
    fun aHashCarriedByASetIsMatched() = runBlocking {
        assertEquals(
            RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(77)),
            client().matchRom(),
        )
    }

    /**
     * A real search that found nothing is [RetroAchievementsMatch.NotMatched] — a CLAIM about the file, and
     * distinct from the null that means nobody looked.
     */
    @Test
    fun aSearchedButUncarriedHashIsNotMatched() = runBlocking {
        val stranger = ByteArray(1024) { 7 }
        assertEquals(
            RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched),
            client().matchRom(bytes = stranger),
        )
    }

    /** A completed f=1 miss plus positive whole-library membership is the only route to RecognizedNoSet. */
    @Test
    fun successfulFOneMissAndKnownHashProducesRecognizedNoSet() = runBlocking {
        val stranger = ByteArray(1024) { 9 }
        val strangerMd5 = MessageDigest.getInstance("MD5").digest(stranger)
            .joinToString("") { "%02x".format(it) }
        val outcome = client(hashLibraryGet = { hashLibrary(strangerMd5) }).matchRom(bytes = stranger)
        assertEquals(
            RaMatchOutcome.Answered(RetroAchievementsMatch.RecognizedNoSet),
            outcome,
        )
    }

    @Test
    fun fallbackRefusalOrUnknownHashPreservesNotMatched() = runBlocking {
        val stranger = ByteArray(1024) { 8 }
        assertEquals(
            RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched),
            client(hashLibraryGet = { RaHashLibraryResult.Unavailable }).matchRom(bytes = stranger),
        )
        assertEquals(
            RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched),
            client(hashLibraryGet = { hashLibrary("ffffffffffffffffffffffffffffffff") })
                .matchRom(bytes = stranger),
        )
    }

    @Test
    fun failedFOneNeverConsultsTheKeylessFallback() = runBlocking {
        var fallbackCalls = 0
        val offline = client(
            respond = { RaHttpResult.Unreachable },
            hashLibraryGet = {
                fallbackCalls++
                hashLibrary("ffffffffffffffffffffffffffffffff")
            },
        )

        assertEquals(
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.TRANSPORT),
            offline.matchRom(bytes = ByteArray(1024) { 4 }),
        )
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun wholeHashLibraryResultIsCachedOncePerClientEvenWhenUnavailable() = runBlocking {
        var fallbackCalls = 0
        val client = client(hashLibraryGet = {
            fallbackCalls++
            RaHashLibraryResult.Unavailable
        })

        client.matchRom(bytes = ByteArray(1024) { 3 })
        client.matchRom(bytes = ByteArray(1024) { 4 })
        assertEquals(1, fallbackCalls)
    }

    /**
     * REGRESSION GUARD, and the honesty line the whole three-state shape exists to hold: "we never looked"
     * must be null, never NotMatched. Folding them together would let a closed gate or a dead network render
     * as "RetroAchievements does not carry this game" — a confident claim built out of a failure.
     */
    @Test
    fun notLookingIsNoAnswerAndNeverNotMatched() = runBlocking {
        assertEquals(
            "opted out never looked",
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.UNCONFIGURED),
            client(gateOpen = false).matchRom(),
        )
        assertEquals(
            "no credentials never looked",
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.UNCONFIGURED),
            client(account = null).matchRom(),
        )
        assertEquals(
            "a supported system with an unported identifier never looked",
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE),
            client().matchRom(platformId = "ps1"),
        )
        assertEquals(
            "an RA-unsupported product system never looked",
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.UNSUPPORTED_SYSTEM),
            client().matchRom(platformId = "xbox"),
        )

        // A console list that will not load is a FAILURE, not evidence about the file.
        val offline = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { true },
                auth = { RetroAchievementsApi.Auth("player", "key") },
                httpGet = { RaHttpResult.Unreachable },
            ),
        )
        assertEquals(
            "an index that would not load never looked",
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.TRANSPORT),
            offline.matchRom(),
        )
    }

    @Test
    fun noAnswerReasonsDistinguishProviderConsoleAndFileFailures() = runBlocking {
        val providerShape = client(respond = { url ->
            if ("API_GetConsoleIDs" in url) RaHttpResult.Body("[]") else RaHttpResult.Body(body(url))
        })
        assertEquals(
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.PROVIDER_DATA),
            providerShape.matchRom(),
        )

        val consoleUnmapped = client(respond = { url ->
            if ("API_GetConsoleIDs" in url) {
                RaHttpResult.Body("""[{"ID":99,"Name":"Unmapped Console"}]""")
            } else {
                RaHttpResult.Body(body(url))
            }
        })
        assertEquals(
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.CONSOLE_UNMAPPED),
            consoleUnmapped.matchRom(),
        )

        val fileUnavailable = client().match(
            platformId = "genesis",
            fileName = "missing.md",
            sizeBytes = rom.size.toLong(),
            openStream = { null },
        )
        assertEquals(
            RaMatchOutcome.NoAnswer(RaNoAnswerReason.FILE_UNAVAILABLE),
            fileUnavailable,
        )
    }

    /**
     * a damaged archive is simply UNMATCHED, not an error state and not an
     * "unavailable". A corrupt zip must therefore read as a claim about the file, not as a failed lookup —
     * and above all must not throw out of the facade.
     */
    @Test
    fun aDamagedArchiveIsUnmatchedNotAnError() = runBlocking {
        val notAZip = ByteArray(512) { 0x41 } // "AAAA..." named .zip: unreadable as an archive
        val outcome = client().match(
            platformId = "genesis",
            fileName = "sonic.zip",
            sizeBytes = notAZip.size.toLong(),
            openStream = { ByteArrayInputStream(notAZip) },
        )
        assertEquals(RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched), outcome)
    }

    /** The lossy convenience still works, and collapses every non-match to null as documented. */
    @Test
    fun resolveGameIdIsTheLossyViewOfMatch() = runBlocking {
        assertEquals(77, client().resolveRom())
        val stranger = ByteArray(1024) { 7 }
        val strangerMd5 = MessageDigest.getInstance("MD5").digest(stranger)
            .joinToString("") { "%02x".format(it) }
        assertNull(
            client().resolveGameId("genesis", "x.md", stranger.size.toLong()) { ByteArrayInputStream(stranger) },
        )
        assertNull(
            "recognized-without-a-set is still not a game id",
            client(hashLibraryGet = { hashLibrary(strangerMd5) })
                .resolveGameId("genesis", "x.md", stranger.size.toLong()) { ByteArrayInputStream(stranger) },
        )
        assertNull(
            "credential rejection is likewise lossy",
            client(respond = { RaHttpResult.Rejected }).resolveRom(),
        )
        assertNull(client(gateOpen = false).resolveRom())
    }

    /** RA platform support and Mimir's local identification capability are separate, network-free facts. */
    @Test
    fun supportAndIdentificationAnswersStayDistinctWithoutTouchingTheNetwork() {
        val calls = mutableListOf<String>()
        val client = client(calls = calls)
        assertTrue(client.supports("genesis"))
        assertTrue(client.supports("snes"))
        assertTrue(client.supports("ps1"))
        assertTrue(client.supports("dreamcast"))
        assertFalse(client.supports("xbox"))
        assertTrue(client.canIdentify("genesis"))
        assertTrue(client.canIdentify("snes"))
        assertTrue(client.canIdentify("ps1"))
        assertTrue(client.canIdentify("dreamcast"))
        assertTrue(client.canIdentify("ps1", "Ridge Racer.chd"))
        assertTrue(client.canIdentify("dreamcast", "Sonic.gdi"))
        assertFalse(client.canIdentify("dreamcast", "Sonic.cdi"))
        assertFalse(client.canIdentify("psp", "Lumines.cso"))
        assertTrue("answering this must not cost a request", calls.isEmpty())
    }

    // ---- the gate survives the facade --------------------------------------

    /**
     * THE posture test, restated at the boundary. The gate is enforced inside the source, but a facade is
     * exactly the kind of layer that can accidentally pre-fetch or cache around it — so this asserts ZERO
     * requests, not merely a null result. A wrapper that fetched and then discarded would still leak which
     * games the user owns.
     */
    @Test
    fun gateClosedMakesZeroRequestsThroughTheFacade() = runBlocking {
        val calls = mutableListOf<String>()
        val client = client(gateOpen = false, calls = calls)
        assertNull(client.resolveRom())
        assertEquals(RaProgressOutcome.Unavailable, client.progressFor(77))
        assertTrue("opted out must issue NO request, not a discarded one", calls.isEmpty())
    }

    /** The second gate half, and the blank-credential case that must read as "no account", not as an account. */
    @Test
    fun absentOrBlankCredentialsMakeZeroRequestsThroughTheFacade() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(RaProgressOutcome.Unavailable, client(account = null, calls = calls).progressFor(77))
        assertEquals(
            RaProgressOutcome.Unavailable,
            client(account = RetroAchievementsAccount("", ""), calls = calls).progressFor(77),
        )
        assertEquals(
            RaProgressOutcome.Unavailable,
            client(account = RetroAchievementsAccount("player", "  "), calls = calls).progressFor(77),
        )
        assertTrue("no usable account must reach the network", calls.isEmpty())
    }

    @Test
    fun validationBypassesTheGateAndNamesWhichCredentialFailed() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(
            RaValidation.Accepted,
            client(gateOpen = false, account = null, calls = calls)
                .validate(RetroAchievementsAccount("player", "key")),
        )
        assertEquals(2, calls.size)
        assertTrue("API_GetConsoleIDs" in calls[0])
        assertTrue("API_GetUserProfile" in calls[1])

        assertEquals(
            RaValidation.KeyRejected,
            client(respond = { RaHttpResult.Rejected })
                .validate(RetroAchievementsAccount("player", "bad-key")),
        )
        assertEquals(
            RaValidation.UsernameRejected,
            client(respond = { url ->
                if ("API_GetUserProfile" in url) RaHttpResult.NotFound else RaHttpResult.Body(body(url))
            }).validate(RetroAchievementsAccount("missing-user", "key")),
        )
        assertEquals(
            RaValidation.Unreachable,
            client(respond = { RaHttpResult.Unreachable })
                .validate(RetroAchievementsAccount("player", "key")),
        )
        assertEquals(
            "a parseable profile for another identity cannot validate the candidate",
            RaValidation.UsernameRejected,
            client(respond = { url ->
                if ("API_GetUserProfile" in url) {
                    RaHttpResult.Body("""{"User":"somebody-else","ULID":"01JOTHER0000000000000000000"}""")
                } else {
                    RaHttpResult.Body(body(url))
                }
            }).validate(RetroAchievementsAccount("player", "key")),
        )
    }

    @Test
    fun credentialRejectionIsTypedAcrossMatchAndProgress() = runBlocking {
        val rejected = client(respond = { RaHttpResult.Rejected })
        assertEquals(RaMatchOutcome.CredentialsRejected, rejected.matchRom())
        assertEquals(RaProgressOutcome.CredentialsRejected, rejected.progressFor(77))

        val missingUsername = client(respond = { url ->
            if ("API_GetGameInfoAndUserProgress" in url) {
                RaHttpResult.NotFound
            } else {
                RaHttpResult.Body(body(url))
            }
        })
        assertEquals(RaProgressOutcome.Unavailable, missingUsername.progressFor(77))
    }

    @Test
    fun runtimeRejectionInvokesThePublicCallbackExactlyOncePerRejectedOutcome() = runBlocking {
        var rejectedCalls = 0
        val rejected = client(
            respond = { RaHttpResult.Rejected },
            onCredentialsRejected = { rejectedCalls++ },
        )

        assertEquals(
            RaValidation.KeyRejected,
            rejected.validate(RetroAchievementsAccount("candidate", "bad-key")),
        )
        assertEquals("candidate validation is not a persisted-session rejection", 0, rejectedCalls)
        assertEquals(RaMatchOutcome.CredentialsRejected, rejected.matchRom())
        assertEquals(1, rejectedCalls)
        assertEquals(RaProgressOutcome.CredentialsRejected, rejected.progressFor(77))
        assertEquals(2, rejectedCalls)

        val unavailable = client(
            respond = { RaHttpResult.Unreachable },
            onCredentialsRejected = { rejectedCalls++ },
        )
        assertEquals(RaMatchOutcome.NoAnswer(RaNoAnswerReason.TRANSPORT), unavailable.matchRom())
        assertEquals(RaProgressOutcome.Unavailable, unavailable.progressFor(77))

        val notFound = client(
            respond = { RaHttpResult.NotFound },
            onCredentialsRejected = { rejectedCalls++ },
        )
        assertEquals(RaMatchOutcome.NoAnswer(RaNoAnswerReason.PROVIDER_DATA), notFound.matchRom())
        assertEquals(RaProgressOutcome.Unavailable, notFound.progressFor(77))
        assertEquals("unavailable and 404 outcomes must never trip the rejection latch", 2, rejectedCalls)
    }

    @Test
    fun runtimeRejectionReportsTheExactCredentialRevisionUsedByTheRequest() = runBlocking {
        val rejectedRevisions = mutableListOf<String?>()
        val rejected = client(
            account = RetroAchievementsAccount("same-player", "old-key", "old-revision"),
            respond = { RaHttpResult.Rejected },
            onCredentialsRejected = { rejectedRevisions += it },
        )

        assertEquals(RaMatchOutcome.CredentialsRejected, rejected.matchRom())
        assertEquals(RaProgressOutcome.CredentialsRejected, rejected.progressFor(77))
        assertEquals(listOf("old-revision", "old-revision"), rejectedRevisions)
    }

    @Test
    fun processLatchMakesSecondAndThirdRuntimeProbesIssueZeroRequests() = runBlocking {
        var gateOpen = true
        val calls = mutableListOf<String>()
        val client = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { gateOpen },
                auth = { RetroAchievementsApi.Auth("player", "rejected-key") },
                httpGet = { url -> calls += url; RaHttpResult.Rejected },
            ),
            onCredentialsRejected = { gateOpen = false },
        )

        assertEquals(RaMatchOutcome.CredentialsRejected, client.matchRom())
        val callsAfterRefusal = calls.size
        assertEquals(RaMatchOutcome.NoAnswer(RaNoAnswerReason.UNCONFIGURED), client.matchRom())
        assertEquals(RaMatchOutcome.NoAnswer(RaNoAnswerReason.UNCONFIGURED), client.matchRom())
        assertEquals("the latched second and third probes are completely offline", callsAfterRefusal, calls.size)
    }

    /**
     * Both gate halves are read PER CALL, not captured at construction. Revoking consent has to take effect
     * immediately — a client built while opted in must go quiet the moment the user opts out, not at the next
     * process start. Asserted in both directions so "goes quiet" cannot pass by never having worked.
     */
    @Test
    fun bothGateHalvesAreReadPerCallNotCapturedAtConstruction() = runBlocking {
        var opted = true
        var account: RetroAchievementsAccount? = RetroAchievementsAccount("player", "key")
        val calls = mutableListOf<String>()
        val client = RetroAchievementsClient(
            RetroAchievementsSource(
                gateOpen = { opted },
                auth = {
                    account?.let {
                        RetroAchievementsApi.Auth(
                            username = it.username,
                            apiKey = it.apiKey,
                            credentialRevision = it.credentialRevision,
                        )
                    }
                },
                httpGet = { url -> calls += url; RaHttpResult.Body(body(url)) },
            ),
        )
        assertEquals(77, client.resolveRom())
        assertTrue("baseline: opted in with an account does reach the network", calls.isNotEmpty())

        calls.clear()
        opted = false
        assertNull("revoking consent must take effect at once", client.resolveRom())
        assertTrue("and go completely quiet", calls.isEmpty())

        opted = true
        account = null
        assertNull("clearing credentials likewise", client.resolveRom())
        assertTrue(calls.isEmpty())
    }

    /**
     * The PUBLIC constructor is what `app` will actually call, so it gets its own coverage — the internal one
     * used above could be wired correctly while this one is not. Exercised with the gate shut and then with
     * no account, both of which are provably offline: no request can be built either way, so this stays a
     * unit test with no socket.
     */
    @Test
    fun thePublicConstructorWiresBothGateHalves() = runBlocking {
        val optedOut = RetroAchievementsClient(
            gateOpen = { false },
            account = { RetroAchievementsAccount("player", "key") },
        )
        assertNull(optedOut.resolveRom())
        assertEquals(RaProgressOutcome.Unavailable, optedOut.progressFor(77))

        val noAccount = RetroAchievementsClient(gateOpen = { true }, account = { null })
        assertNull(noAccount.resolveRom())
        assertEquals(RaProgressOutcome.Unavailable, noAccount.progressFor(77))
    }

    // ---- the key does not leak at the boundary -----------------------------

    /**
     * REGRESSION GUARD: [RetroAchievementsAccount] is deliberately NOT a data class. A generated toString
     * prints every field, so `"$account"` in one log line would print the user's API key — which RA's docs
     * treat as a secret and must remain out of logs. Making it a data class later
     * would silently undo that, so the redaction is pinned here.
     */
    @Test
    fun accountNeverPrintsTheApiKey() {
        val rendered = RetroAchievementsAccount("player one", "s3cr3t-key").toString()
        assertFalse("the key must never render", rendered.contains("s3cr3t"))
        assertTrue("the username is not a secret and stays readable", rendered.contains("player one"))
        assertTrue(rendered.contains("***"))
    }

    /**
     * The facade must not become a way to reach key-bearing URL building. This asserts the public progress
     * type carries no trace of the request that produced it — no url, no key, no auth — so nothing a caller
     * can hold onto could be logged into a leak.
     */
    @Test
    fun publicTypesCarryNoCredentialOrUrlSurface() = runBlocking {
        val progress = (client().progressFor(77) as RaProgressOutcome.Read).progress
        val rendered = progress.toString()
        assertFalse(rendered.contains("key"))
        assertFalse(rendered.contains("http"))
        assertFalse(rendered.contains("player"))

        val fields = RetroAchievementsProgress::class.java.declaredFields.map { it.name }
        assertFalse("progress must not carry auth", fields.any { it.contains("auth", ignoreCase = true) })
        assertFalse("progress must not carry a url", fields.any { it.contains("url", ignoreCase = true) })
    }
}
