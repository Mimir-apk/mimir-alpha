package com.gruning.mimir.rewrite.core.metadata.online

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guards. A miss must be silent and honest; the gate must be absolute. */
class RetroAchievementsSourceTest {

    private val auth = RetroAchievementsApi.Auth("player", "key")
    private val rom = ByteArray(2048) { (it % 61).toByte() }
    private val romMd5 = MessageDigest.getInstance("MD5").digest(rom).joinToString("") { "%02x".format(it) }

    private fun source(
        gateOpen: Boolean = true,
        credentials: RetroAchievementsApi.Auth? = auth,
        calls: MutableList<String> = mutableListOf(),
        respond: suspend (String) -> RaHttpResult = { RaHttpResult.Body(defaultBody(it)) },
        hashLibraryGet: suspend () -> RaHashLibraryResult = { RaHashLibraryResult.Unavailable },
        chdHash:
            (suspend (Int, RetroAchievementsChdResident) -> RetroAchievementsChdHashResult)? = null,
        clock: () -> Long = System::currentTimeMillis,
    ) = RetroAchievementsSource(
        gateOpen = { gateOpen },
        auth = { credentials },
        httpGet = { url -> calls += url; respond(url) },
        hashLibraryGet = hashLibraryGet,
        chdHash = chdHash,
        clock = clock,
    )

    private fun hashLibrary(vararg entries: Pair<String, Int>): RaHashLibraryResult.Loaded {
        val body = entries.joinToString(",") { (hash, gameId) -> "\"$hash\":$gameId" }
        val index = parsePackedHashLibrary(StringReader("""{"Success":true,"MD5List":{$body}}"""))!!
        return RaHashLibraryResult.Loaded(index)
    }

    private fun defaultBody(url: String): String = when {
        "API_GetConsoleIDs" in url -> """[{"ID":1,"Name":"Mega Drive"},{"ID":12,"Name":"PlayStation"}]"""
        "API_GetGameList" in url ->
            """[{"ID":77,"Title":"Sonic","ConsoleID":1,"NumAchievements":1,"Hashes":["$romMd5"]}]"""
        "API_GetGameInfoAndUserProgress" in url ->
            """{"ID":77,"Title":"Sonic","NumAchievements":1,"NumAwardedToUser":1,"NumAwardedToUserHardcore":0,"Achievements":{"1":{"ID":1,"Title":"First","Description":"Start","Points":5,"DateEarned":"2026-01-01 00:00:00"}}}"""
        "API_GetUserProfile" in url -> """{"User":"player","ULID":"01JPLAYER000000000000000000"}"""
        else -> "[]"
    }

    /**
     * THE posture test: gate closed ⇒ not one byte. Asserting "no result" is not enough — a future
     * refactor could fetch and then discard, which still leaks the user's library to the network.
     */
    @Test
    fun gateClosedMakesZeroRequests() = runBlocking {
        val calls = mutableListOf<String>()
        val id = source(gateOpen = false, calls = calls).resolveGameId(
            platformId = "genesis",
            fileName = "sonic.md",
            sizeBytes = rom.size.toLong(),
            openStream = { ByteArrayInputStream(rom) },
        )
        assertNull(id)
        assertTrue("gate closed must issue NO request, not a discarded one", calls.isEmpty())
        assertEquals(RaProgressLookup.Unavailable, source(gateOpen = false, calls = calls).progressFor(77))
        assertTrue(calls.isEmpty())
    }

    /** No credentials is the second half of the gate — being opted in alone must not reach the network. */
    @Test
    fun missingOrBlankCredentialsMakeZeroRequests() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(RaProgressLookup.Unavailable, source(credentials = null, calls = calls).progressFor(77))
        assertEquals(
            RaProgressLookup.Unavailable,
            source(credentials = RetroAchievementsApi.Auth("", ""), calls = calls).progressFor(77),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun resolvesAGameByItsRaHash() = runBlocking {
        val id = source().resolveGameId(
            platformId = "genesis",
            fileName = "sonic.md",
            sizeBytes = rom.size.toLong(),
            openStream = { ByteArrayInputStream(rom) },
        )
        assertEquals(77, id)
    }

    /** Regression: the live combined system label must reach SNES hashing, never CONSOLE_UNMAPPED. */
    @Test
    fun currentSnesProviderLabelReachesTheHashIndex() = runBlocking {
        val calls = mutableListOf<String>()
        val result = source(
            calls = calls,
            respond = { url ->
                RaHttpResult.Body(
                    when {
                        "API_GetConsoleIDs" in url ->
                            """[{"ID":3,"Name":"SNES/Super Famicom"}]"""
                        "API_GetGameList" in url ->
                            """[{"ID":77,"Title":"3 Ninjas Kick Back","ConsoleID":3,"NumAchievements":40,"Hashes":["$romMd5"]}]"""
                        else -> defaultBody(url)
                    },
                )
            },
        ).lookup(
            platformId = "snes",
            fileName = "3 Ninjas Kick Back (USA).sfc",
            sizeBytes = rom.size.toLong(),
            openStream = { ByteArrayInputStream(rom) },
        )

        assertEquals(RaLookup.Hit(77), result)
        assertEquals(1, calls.count { "API_GetConsoleIDs" in it })
        assertEquals(1, calls.count { "API_GetGameList" in it })
    }

    /** A supported seek reader without a resolvable path stays typed before file or network I/O. */
    @Test
    fun supportedButUnidentifiedSystemNeverOpensTheFileOrReachesTheNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        var opens = 0
        val result = source(calls = calls).lookup(
            platformId = "ps1",
            fileName = "game.chd",
            sizeBytes = rom.size.toLong(),
            openStream = {
                opens++
                ByteArrayInputStream(rom)
            },
        )
        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE), result)
        assertEquals(0, opens)
        assertTrue("no lookup without a seekable source", calls.isEmpty())
    }

    @Test
    fun verifiedChdChainCanHashWithoutALocalTargetPath() = runBlocking {
        val calls = mutableListOf<String>()
        val target = chdResident()
        val result = source(
            calls = calls,
            respond = { url ->
                RaHttpResult.Body(
                    when {
                        "API_GetConsoleIDs" in url -> """[{"ID":12,"Name":"PlayStation"}]"""
                        "API_GetGameList" in url ->
                            """[{"ID":88,"Title":"Disc","ConsoleID":12,"NumAchievements":2,"Hashes":["$romMd5"]}]"""
                        else -> defaultBody(url)
                    },
                )
            },
            chdHash = { consoleId, resident ->
                assertEquals(12, consoleId)
                assertEquals(target, resident)
                RetroAchievementsChdHashResult.Hashed(romMd5)
            },
        ).lookup(
            platformId = "ps1",
            fileName = "disc.chd",
            sizeBytes = 4_000_000L,
            targetChd = target,
            openStream = { error("CHD hashing must not open the streaming seam") },
        )

        assertEquals(RaLookup.Hit(88), result)
        assertEquals(1, calls.count { "API_GetGameList" in it })
    }

    @Test
    fun m3uSelectedChdUsesTheVerifiedChainWithoutAPlaylistPath() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = chdResident()
        val result = source(
            calls = calls,
            respond = { url ->
                RaHttpResult.Body(
                    when {
                        "API_GetConsoleIDs" in url -> """[{"ID":12,"Name":"PlayStation"}]"""
                        "API_GetGameList" in url ->
                            """[{"ID":89,"Title":"Multi Disc","ConsoleID":12,"NumAchievements":2,"Hashes":["$romMd5"]}]"""
                        else -> defaultBody(url)
                    },
                )
            },
            chdHash = { consoleId, resident ->
                assertEquals(12, consoleId)
                assertEquals(selected, resident)
                RetroAchievementsChdHashResult.Hashed(romMd5)
            },
        ).lookup(
            platformId = "ps1",
            fileName = "game.m3u",
            sizeBytes = 128L,
            targetChd = selected,
            openStream = { error("the selected CHD must bypass playlist streaming") },
        )

        assertEquals(RaLookup.Hit(89), result)
        assertEquals(1, calls.count { "API_GetGameList" in it })
    }

    @Test
    fun verifiedReadableChdWithNoCanonicalDigestIsAMiss() = runBlocking {
        val calls = mutableListOf<String>()
        val result = source(
            calls = calls,
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetConsoleIDs" in url) {
                        """[{"ID":12,"Name":"PlayStation"}]"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
            chdHash = { _, _ -> RetroAchievementsChdHashResult.Hashed(null) },
        ).lookup(
            platformId = "ps1",
            fileName = "disc.chd",
            sizeBytes = 4_000_000L,
            targetChd = chdResident(),
            openStream = { error("CHD hashing must not open the streaming seam") },
        )

        assertEquals(RaLookup.Miss, result)
        assertEquals(
            "only a verified readable CHD with a null canonical digest may consult the index as a miss",
            0,
            calls.count { "API_GetGameList" in it },
        )
    }

    @Test
    fun transientChdChainFailureIsNoAnswerAndNeverAFileMiss() = runBlocking {
        val calls = mutableListOf<String>()
        val result = source(
            calls = calls,
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetConsoleIDs" in url) {
                        """[{"ID":12,"Name":"PlayStation"}]"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
            chdHash = { _, _ -> RetroAchievementsChdHashResult.FileUnavailable },
        ).lookup(
            platformId = "ps1",
            fileName = "disc.chd",
            sizeBytes = 4_000_000L,
            targetChd = chdResident(),
            openStream = { error("CHD hashing must not open the streaming seam") },
        )

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE), result)
        assertEquals("a transient chain refusal cannot reach or cache a file-index miss", 0,
            calls.count { "API_GetGameList" in it })
    }

    @Test
    fun unsupportedChdChainPolicyIsIdentificationUnavailable() = runBlocking {
        val result = source(
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetConsoleIDs" in url) {
                        """[{"ID":12,"Name":"PlayStation"}]"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
            chdHash = { _, _ -> RetroAchievementsChdHashResult.IdentificationUnavailable },
        ).lookup(
            platformId = "ps1",
            fileName = "disc.chd",
            sizeBytes = 4_000_000L,
            targetChd = chdResident(),
            openStream = { error("CHD hashing must not open the streaming seam") },
        )

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE), result)
    }

    @Test(expected = CancellationException::class)
    fun chdHashCancellationPropagates() = runBlocking {
        source(
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetConsoleIDs" in url) {
                        """[{"ID":12,"Name":"PlayStation"}]"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
            chdHash = { _, _ -> throw CancellationException("focus moved") },
        ).lookup(
            platformId = "ps1",
            fileName = "disc.chd",
            sizeBytes = 4_000_000L,
            targetChd = chdResident(),
            openStream = { error("CHD hashing must not open the streaming seam") },
        )
        Unit
    }

    @Test
    fun unsupportedCompressedDiscContainerStopsBeforeFileAndNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        var opens = 0
        val result = source(calls = calls).lookup(
            platformId = "dreamcast",
            fileName = "game.cdi",
            sizeBytes = rom.size.toLong(),
            localPath = "/storage/game.cdi",
            openStream = {
                opens++
                ByteArrayInputStream(rom)
            },
        )
        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE), result)
        assertEquals(0, opens)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun opaqueCartridgeArchiveStopsBeforeFileAndNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        var opens = 0
        val result = source(calls = calls).lookup(
            platformId = "gba",
            fileName = "game.7z",
            sizeBytes = rom.size.toLong(),
            openStream = {
                opens++
                ByteArrayInputStream(rom)
            },
        )

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE), result)
        assertEquals(0, opens)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun mappedJaguarCdWithoutAReaderIsIdentificationUnavailableBeforeIo() = runBlocking {
        val calls = mutableListOf<String>()
        var opens = 0
        val result = source(calls = calls).lookup(
            platformId = "atari_jaguar_cd",
            fileName = "game.cue",
            sizeBytes = rom.size.toLong(),
            openStream = {
                opens++
                ByteArrayInputStream(rom)
            },
        )

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE), result)
        assertEquals(0, opens)
        assertTrue(calls.isEmpty())
    }

    /** A platform outside RA's supported intersection has its own posture and also performs no I/O. */
    @Test
    fun unsupportedSystemNeverOpensTheFileOrReachesTheNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        var opens = 0
        val result = source(calls = calls).lookup(
            platformId = "xbox",
            fileName = "game.iso",
            sizeBytes = rom.size.toLong(),
            openStream = {
                opens++
                ByteArrayInputStream(rom)
            },
        )
        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.UNSUPPORTED_SYSTEM), result)
        assertEquals(0, opens)
        assertTrue(calls.isEmpty())
    }

    /** An unmatched hash is a clean null, not an exception and not a nearest-neighbour guess. */
    @Test
    fun unmatchedHashResolvesToNull() = runBlocking {
        val id = source(respond = { url ->
            RaHttpResult.Body(
                if ("API_GetGameList" in url) {
                    """[{"ID":77,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["00000000000000000000000000000000"]}]"""
                } else {
                    defaultBody(url)
                },
            )
        }).resolveGameId(
            platformId = "genesis",
            fileName = "sonic.md",
            sizeBytes = rom.size.toLong(),
            openStream = { ByteArrayInputStream(rom) },
        )
        assertNull(id)
    }

    /** Static data is fetched once; RA's docs explicitly ask clients to cache it. */
    @Test
    fun consoleListAndHashIndexAreFetchedOncePerProcess() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(calls = calls)
        repeat(3) {
            src.resolveGameId("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }
        }
        assertEquals("console list cached", 1, calls.count { "API_GetConsoleIDs" in it })
        assertEquals("hash index cached", 1, calls.count { "API_GetGameList" in it })
    }

    @Test
    fun concurrentProbesSingleFlightConsoleAndHashIndexes() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(
            calls = calls,
            respond = { url ->
                delay(20)
                RaHttpResult.Body(defaultBody(url))
            },
        )

        val ids = List(8) {
            async {
                src.resolveGameId("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }
            }
        }.awaitAll()

        assertEquals(List(8) { 77 }, ids)
        assertEquals("concurrent probes share one console request", 1, calls.count { "API_GetConsoleIDs" in it })
        assertEquals("concurrent probes share one f=1 request", 1, calls.count { "API_GetGameList" in it })
    }

    @Test
    fun concurrentFailedIndexLoadIsSharedButRemainsRetryable() = runBlocking {
        val calls = mutableListOf<String>()
        val hashesClosed = AtomicInteger()
        val allHashesClosed = CompletableDeferred<Unit>()
        val src = source(
            calls = calls,
            respond = { url ->
                if ("API_GetGameList" in url) {
                    allHashesClosed.await()
                    delay(20)
                    RaHttpResult.Unreachable
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
        )

        val results = List(8) {
            async {
                src.lookup("genesis", "sonic.md", rom.size.toLong()) {
                    object : ByteArrayInputStream(rom) {
                        private var reportedClosed = false

                        override fun close() {
                            if (!reportedClosed) {
                                reportedClosed = true
                                if (hashesClosed.incrementAndGet() == 8) allHashesClosed.complete(Unit)
                            }
                            super.close()
                        }
                    }
                }
            }
        }.awaitAll()

        assertEquals(List(8) { RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT) }, results)
        assertEquals("one in-flight failure is shared by all waiters", 1, calls.count { "API_GetGameList" in it })

        src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }
        assertEquals("failure is not memoized forever", 2, calls.count { "API_GetGameList" in it })
    }

    @Test
    fun inFlightCredentialRejectionIsNeverSharedWithAnotherKey() = runBlocking {
        val badRequestStarted = CompletableDeferred<Unit>()
        val releaseBadRequest = CompletableDeferred<Unit>()
        val src = source(
            respond = { url ->
                if ("API_GetConsoleIDs" in url && "y=bad-key" in url) {
                    badRequestStarted.complete(Unit)
                    releaseBadRequest.await()
                    RaHttpResult.Rejected
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
        )

        val badValidation = async {
            src.validate(RetroAchievementsApi.Auth("candidate", "bad-key"))
        }
        badRequestStarted.await()
        val goodLookup = async {
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }
        }
        assertEquals(
            "the good key has its own flight and is not blocked behind the refused candidate",
            RaLookup.Hit(77),
            goodLookup.await(),
        )
        releaseBadRequest.complete(Unit)
        assertEquals(RaValidation.KeyRejected, badValidation.await())
    }

    @Test
    fun validationNeverInheritsARuntimeKeysSuccessfulConsoleCache() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(
            calls = calls,
            respond = { url ->
                if ("API_GetConsoleIDs" in url && "y=bad-key" in url) {
                    RaHttpResult.Rejected
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
        )

        assertEquals(
            RaLookup.Hit(77),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
        assertEquals(
            RaValidation.KeyRejected,
            src.validate(RetroAchievementsApi.Auth("player", "bad-key")),
        )
        assertEquals("candidate validation makes its own credential-bearing request", 2,
            calls.count { "API_GetConsoleIDs" in it })
    }

    @Test
    fun profileMustNameTheCandidateBeforeValidationCanAccept() = runBlocking {
        val src = source(
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetUserProfile" in url) {
                        """{"User":"somebody-else","ULID":"01JOTHER0000000000000000000"}"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
        )

        assertEquals(
            "a mismatched 2xx did not validate the submitted username",
            RaValidation.UsernameRejected,
            src.validate(auth),
        )
    }

    /**
     * RA looks `u` up case-insensitively and answers with the display name's casing, so a casing difference is the
     * same account. Mimir's keyboard capitalises an empty field's first letter; rejecting that was the
     * "Username not accepted" trap. A different name is still rejected (see the test above).
     */
    @Test
    fun profileCasingDifferenceIsTheSameAccount() = runBlocking {
        val src = source(
            respond = { url ->
                RaHttpResult.Body(
                    if ("API_GetUserProfile" in url) {
                        """{"User":"Player","ULID":"01JPLAYER000000000000000000"}"""
                    } else {
                        defaultBody(url)
                    },
                )
            },
        )

        assertEquals(RaValidation.Accepted, src.validate(auth))
        assertEquals(
            RaValidation.Accepted,
            src.validate(RetroAchievementsApi.Auth(username = "PLAYER", apiKey = "key")),
        )
    }

    /** Progress is NOT cached — a stale unlock count is a wrong claim about the user. */
    @Test
    fun progressIsRefetchedEveryTime() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(calls = calls)
        repeat(3) { src.progressFor(77) }
        assertEquals(3, calls.count { "API_GetGameInfoAndUserProgress" in it })
    }

    /** A dead/garbage endpoint degrades to unavailable rather than throwing into a composition. */
    @Test
    fun networkFailureIsSilentNotFatal() = runBlocking {
        assertEquals(RaProgressLookup.Unavailable, source(respond = { RaHttpResult.Unreachable }).progressFor(77))
        assertEquals(
            RaProgressLookup.Unavailable,
            source(respond = { RaHttpResult.Body("<html>502</html>") }).progressFor(77),
        )
        val id = source(respond = { RaHttpResult.Unreachable })
            .resolveGameId("genesis", "s.md", 8L) { ByteArrayInputStream(rom) }
        assertNull(id)
    }

    @Test
    fun validationBypassesRuntimeGateAndChecksKeyThenUsername() = runBlocking {
        val calls = mutableListOf<String>()
        val result = source(gateOpen = false, credentials = null, calls = calls).validate(auth)

        assertEquals(RaValidation.Accepted, result)
        assertEquals(2, calls.size)
        assertTrue("first prove the key", "API_GetConsoleIDs" in calls[0])
        assertTrue("then prove the username", "API_GetUserProfile" in calls[1])
    }

    @Test
    fun blankCandidateCredentialsAreRejectedWithoutANetworkCall() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(gateOpen = false, credentials = null, calls = calls)

        assertEquals(
            RaValidation.KeyRejected,
            src.validate(RetroAchievementsApi.Auth(username = "player", apiKey = "  ")),
        )
        assertEquals(
            RaValidation.UsernameRejected,
            src.validate(RetroAchievementsApi.Auth(username = "  ", apiKey = "key")),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun validationSeparatesKeyUsernameAndTransportFailures() = runBlocking {
        assertEquals(
            RaValidation.KeyRejected,
            source(respond = { RaHttpResult.Rejected }).validate(auth),
        )
        assertEquals(
            RaValidation.Unreachable,
            source(respond = { RaHttpResult.Unreachable }).validate(auth),
        )
        assertEquals(
            RaValidation.Unreachable,
            source(respond = { RaHttpResult.Body("not-json") }).validate(auth),
        )

        val usernameMissing = source(respond = { url ->
            if ("API_GetUserProfile" in url) {
                RaHttpResult.NotFound
            } else {
                RaHttpResult.Body(defaultBody(url))
            }
        })
        assertEquals(RaValidation.UsernameRejected, usernameMissing.validate(auth))

        val rejectedAtProfile = source(respond = { url ->
            if ("API_GetUserProfile" in url) RaHttpResult.Rejected else RaHttpResult.Body(defaultBody(url))
        })
        assertEquals(RaValidation.KeyRejected, rejectedAtProfile.validate(auth))

        val malformedProfile = source(respond = { url ->
            if ("API_GetUserProfile" in url) {
                RaHttpResult.Body("not-json")
            } else {
                RaHttpResult.Body(defaultBody(url))
            }
        })
        assertEquals(RaValidation.Unreachable, malformedProfile.validate(auth))
    }

    @Test
    fun acceptedValidationSeedsTheStaticConsoleCache() = runBlocking {
        val calls = mutableListOf<String>()
        val src = source(calls = calls)

        assertEquals(RaValidation.Accepted, src.validate(auth))
        src.resolveGameId("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }

        assertEquals("validation seeds the lookup cache", 1, calls.count { "API_GetConsoleIDs" in it })
        assertEquals(1, calls.count { "API_GetUserProfile" in it })
    }

    @Test
    fun runtimeCredentialRejectionSurvivesMatchAndProgress() = runBlocking {
        val rejectedAtConsoleList = source(respond = { RaHttpResult.Rejected })
        assertEquals(
            RaLookup.Rejected(credentialRevision = null),
            rejectedAtConsoleList.lookup("genesis", "sonic.md", rom.size.toLong()) {
                ByteArrayInputStream(rom)
            },
        )
        assertEquals(
            RaProgressLookup.Rejected(credentialRevision = null),
            rejectedAtConsoleList.progressFor(77),
        )

        val rejectedAtGameList = source(respond = { url ->
            if ("API_GetGameList" in url) RaHttpResult.Rejected else RaHttpResult.Body(defaultBody(url))
        })
        assertEquals(
            RaLookup.Rejected(credentialRevision = null),
            rejectedAtGameList.lookup("genesis", "sonic.md", rom.size.toLong()) {
                ByteArrayInputStream(rom)
            },
        )

        val missingUsername = source(respond = { url ->
            if ("API_GetGameInfoAndUserProgress" in url) {
                RaHttpResult.NotFound
            } else {
                RaHttpResult.Body(defaultBody(url))
            }
        })
        assertEquals(
            "runtime 404 is unavailable; only validation profile 404 rejects a username",
            RaProgressLookup.Unavailable,
            missingUsername.progressFor(77),
        )
    }

    @Test
    fun consoleCredentialRejectionNeverOpensTheGameFile() = runBlocking {
        var opens = 0
        val result = source(respond = { RaHttpResult.Rejected }).lookup(
            "genesis",
            "sonic.md",
            rom.size.toLong(),
        ) {
            opens++
            ByteArrayInputStream(rom)
        }

        assertEquals(RaLookup.Rejected(credentialRevision = null), result)
        assertEquals("a refused key must stop before file I/O", 0, opens)
    }

    private fun chdResident() = RetroAchievementsChdResident(
        entryKey = "v11:test",
        sourceRootUri = "content://root",
        relativePath = "PS1/disc.chd",
        documentUri = "content://root/PS1/disc.chd",
        managedPath = null,
    )

    @Test
    fun partialFOneFailureCannotConsultOrFabricateTheFallback() = runBlocking {
        var fallbackCalls = 0
        val src = source(
            respond = { url ->
                when {
                    "API_GetConsoleIDs" in url -> RaHttpResult.Body(
                        """[{"ID":1,"Name":"Mega Drive"},{"ID":2,"Name":"Genesis"}]""",
                    )
                    "i=1" in url -> RaHttpResult.Body(
                        """[{"ID":77,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["00000000000000000000000000000000"]}]""",
                    )
                    "i=2" in url -> RaHttpResult.Unreachable
                    else -> RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = {
                fallbackCalls++
                RaHashLibraryResult.Unavailable
            },
        )

        assertEquals(
            RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun malformedPartialFOneIndexFailsAsAWholeAndNeverCallsFallback() = runBlocking {
        var fallbackCalls = 0
        val src = source(
            respond = { url ->
                if ("API_GetGameList" in url) {
                    RaHttpResult.Body(
                        """[{"ID":77,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["00000000000000000000000000000000"]},""" +
                            """{"ID":88,"Title":"Broken","ConsoleID":1,"NumAchievements":1,"Hashes":["not-an-md5"]}]""",
                    )
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = {
                fallbackCalls++
                RaHashLibraryResult.Unavailable
            },
        )

        assertEquals(
            RaLookup.Unavailable(RaNoAnswerReason.PROVIDER_DATA),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
        assertEquals("a partial index is not a completed f=1 miss", 0, fallbackCalls)
    }

    @Test
    fun emptyHashProviderRowDoesNotHideALaterExactPositiveHit() = runBlocking {
        val src = source(respond = { url ->
            if ("API_GetGameList" in url) {
                RaHttpResult.Body(
                    """[{"ID":88,"Title":"No Files","ConsoleID":1,"NumAchievements":1,"Hashes":[]},""" +
                        """{"ID":77,"Title":"Sonic","ConsoleID":1,"NumAchievements":1,"Hashes":["$romMd5"]}]""",
                )
            } else {
                RaHttpResult.Body(defaultBody(url))
            }
        })

        assertEquals(
            RaLookup.Hit(77),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
    }

    @Test
    fun emptyHashProviderRowKeepsAnUnmatchedLookupUnknownWhenFallbackIsUnavailable() = runBlocking {
        var fallbackCalls = 0
        val src = source(
            respond = { url ->
                if ("API_GetGameList" in url) {
                    RaHttpResult.Body(
                        """[{"ID":77,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["00000000000000000000000000000000"]},""" +
                            """{"ID":88,"Title":"No Files","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]""",
                    )
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = {
                fallbackCalls++
                RaHashLibraryResult.Unavailable
            },
        )

        assertEquals(
            RaLookup.Unavailable(RaNoAnswerReason.PROVIDER_DATA),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun incompleteFOneUsesGlobalGameIdToRecoverAnAchievementSetHit() = runBlocking {
        val src = source(
            respond = { url ->
                if ("API_GetGameList" in url) {
                    RaHttpResult.Body(
                        """[{"ID":77,"Title":"Sonic","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]""",
                    )
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = { hashLibrary(romMd5 to 77) },
        )

        assertEquals(
            RaLookup.Hit(77),
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
    }

    @Test
    fun incompleteFOneUsesGlobalGameIdToDistinguishRecognizedWithoutASet() = runBlocking {
        val src = source(
            respond = { url ->
                if ("API_GetGameList" in url) {
                    RaHttpResult.Body(
                        """[{"ID":88,"Title":"Other Set","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]""",
                    )
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = { hashLibrary(romMd5 to 99) },
        )

        assertEquals(
            RaLookup.RecognizedNoSet,
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
    }

    @Test
    fun incompleteFOneAndCompletedGlobalAbsenceIsAnHonestMiss() = runBlocking {
        val src = source(
            respond = { url ->
                if ("API_GetGameList" in url) {
                    RaHttpResult.Body(
                        """[{"ID":88,"Title":"Other Set","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]""",
                    )
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
            hashLibraryGet = {
                hashLibrary("ffffffffffffffffffffffffffffffff" to 99)
            },
        )

        assertEquals(
            RaLookup.Miss,
            src.lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) },
        )
    }

    // Freshness of the provider indexes ---------------------------------------------------------------------------------------------

    private val week = RETRO_ACHIEVEMENTS_PROVIDER_INDEX_FRESH_MS
    private val hour = RETRO_ACHIEVEMENTS_PROVIDER_INDEX_RETRY_MS
    private val unrelatedHash = "00000000000000000000000000000000"

    private fun gameList(withSet: Boolean) = if (withSet) {
        """[{"ID":88,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["$unrelatedHash"]},""" +
            """{"ID":77,"Title":"Sonic","ConsoleID":1,"NumAchievements":1,"Hashes":["$romMd5"]}]"""
    } else {
        """[{"ID":88,"Title":"Other","ConsoleID":1,"NumAchievements":1,"Hashes":["$unrelatedHash"]}]"""
    }

    private suspend fun RetroAchievementsSource.sonic(): RaLookup =
        lookup("genesis", "sonic.md", rom.size.toLong()) { ByteArrayInputStream(rom) }

    private fun List<String>.indexReads() = count { "API_GetGameList" in it }

    @Test
    fun aSetAddedAfterAMissIsFoundOnceTheIndexIsAWeekOldAndNotBefore() = runBlocking {
        var now = 1_000_000L
        var setAdded = false
        val calls = mutableListOf<String>()
        val src = source(
            calls = calls,
            clock = { now },
            respond = { url -> RaHttpResult.Body(if ("API_GetGameList" in url) gameList(setAdded) else defaultBody(url)) },
        )

        assertEquals("learned at t0: RetroAchievements carries no set for this file", RaLookup.Miss, src.sonic())
        assertEquals(1, calls.indexReads())

        // A set appears at the provider. Inside the week nothing is re-read, however often the file is looked up.
        setAdded = true
        now += week - 1L
        assertEquals(RaLookup.Miss, src.sonic())
        assertEquals(RaLookup.Miss, src.sonic())
        assertEquals("no expiry, no extra index read", 1, calls.indexReads())

        // A week after it was read the next lookup reads the index again and resolves the game.
        now += 1L
        assertEquals(RaLookup.Hit(77), src.sonic())
        assertEquals(2, calls.indexReads())

        // And that read renews the window: the next week starts there.
        assertEquals(RaLookup.Hit(77), src.sonic())
        now += week - 1L
        assertEquals(RaLookup.Hit(77), src.sonic())
        assertEquals(2, calls.indexReads())
    }

    @Test
    fun aRefreshThatFailsNeverAnswersAMissFromTheStaleIndexAndIsRetriedAtMostOncePerRetryWindow() = runBlocking {
        var now = 1_000_000L
        var providerDown = false
        var setAdded = false
        val calls = mutableListOf<String>()
        val src = source(
            calls = calls,
            clock = { now },
            respond = { url ->
                when {
                    "API_GetGameList" in url -> if (providerDown) RaHttpResult.Unreachable else RaHttpResult.Body(gameList(setAdded))
                    else -> RaHttpResult.Body(defaultBody(url))
                }
            },
        )
        assertEquals(RaLookup.Miss, src.sonic())
        assertEquals(1, calls.indexReads())

        providerDown = true
        now += week
        assertEquals("the stale index cannot vouch for absence", RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT), src.sonic())
        assertEquals(2, calls.indexReads())

        // Looked up again and again while the provider is down: no further request until the retry window passes.
        repeat(5) {
            now += 60_000L
            assertEquals(RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT), src.sonic())
        }
        assertEquals("expiry cannot turn into a burst of requests", 2, calls.indexReads())

        providerDown = false
        setAdded = true
        now += hour
        assertEquals(RaLookup.Hit(77), src.sonic())
        assertEquals(3, calls.indexReads())
    }

    @Test
    fun anIndexNeverReadIsStillRetriedOnEveryLookupAsBefore() = runBlocking {
        var providerDown = true
        val calls = mutableListOf<String>()
        val src = source(
            calls = calls,
            respond = { url ->
                if ("API_GetGameList" in url) {
                    if (providerDown) RaHttpResult.Unreachable else RaHttpResult.Body(gameList(true))
                } else {
                    RaHttpResult.Body(defaultBody(url))
                }
            },
        )
        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT), src.sonic())
        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.TRANSPORT), src.sonic())
        assertEquals(2, calls.indexReads())
        providerDown = false
        assertEquals(RaLookup.Hit(77), src.sonic())
    }

    @Test
    fun theKeylessLibraryIsReadAgainAfterAWeekAndAFailedRefreshKeepsTheOneHeld() = runBlocking {
        var now = 1_000_000L
        var libraryCalls = 0
        var libraryDown = false
        // An index with an empty-hash row is incomplete, so the library decides between "recognised without a set" and a plain miss.
        val incomplete = """[{"ID":88,"Title":"Other Set","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]"""
        val src = source(
            clock = { now },
            respond = { url -> RaHttpResult.Body(if ("API_GetGameList" in url) incomplete else defaultBody(url)) },
            hashLibraryGet = {
                libraryCalls++
                if (libraryDown) RaHashLibraryResult.Unavailable else hashLibrary(romMd5 to 99)
            },
        )
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals("held inside the week", 1, libraryCalls)

        now += week
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals("read again after the week", 2, libraryCalls)

        // The next refresh fails: the library already held keeps answering, and is not asked again until the retry window has passed.
        libraryDown = true
        now += week
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals(3, libraryCalls)
        now += 60_000L
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals(3, libraryCalls)
        now += hour
        assertEquals(RaLookup.RecognizedNoSet, src.sonic())
        assertEquals(4, libraryCalls)
    }

    @Test
    fun aKeylessLibraryThatFailedIsTriedAgainAfterTheRetryWindowNotOnEveryMiss() = runBlocking {
        var now = 1_000_000L
        var libraryCalls = 0
        val incomplete = """[{"ID":88,"Title":"Other Set","ConsoleID":1,"NumAchievements":1,"Hashes":[]}]"""
        val src = source(
            clock = { now },
            respond = { url -> RaHttpResult.Body(if ("API_GetGameList" in url) incomplete else defaultBody(url)) },
            hashLibraryGet = { libraryCalls++; RaHashLibraryResult.Unavailable },
        )
        src.sonic()
        src.sonic()
        assertEquals(1, libraryCalls)
        now += hour
        src.sonic()
        assertEquals(2, libraryCalls)
    }

    @Test
    fun progressRequiresTheRequestedGameIdentity() = runBlocking {
        val wrongGame = source(respond = { url ->
            if ("API_GetGameInfoAndUserProgress" in url) {
                RaHttpResult.Body(defaultBody(url).replaceFirst("\"ID\":77", "\"ID\":78"))
            } else {
                RaHttpResult.Body(defaultBody(url))
            }
        })

        assertEquals(RaProgressLookup.Unavailable, wrongGame.progressFor(77))
    }

    @Test
    fun cancellationIsNeverCollapsedIntoAnUnavailableOutcome() = runBlocking {
        val networkCancelled = source(respond = { throw CancellationException("network cancelled") })
        try {
            networkCancelled.progressFor(77)
            throw AssertionError("network cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("network cancelled", expected.message)
        }

        val cancellingStream = object : InputStream() {
            override fun read(): Int = throw CancellationException("hash cancelled")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw CancellationException("hash cancelled")
        }
        try {
            source().lookup("genesis", "sonic.md", rom.size.toLong()) { cancellingStream }
            throw AssertionError("hash cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("hash cancelled", expected.message)
        }
    }

    /** Even behind the gate, only RA hosts are reachable — a stray URL can never egress. */
    @Test
    fun hostAllowlistRejectsEverythingElse() {
        assertTrue(isRetroAchievementsHost("retroachievements.org"))
        assertTrue(isRetroAchievementsHost("API.RetroAchievements.org"))
        assertTrue(isRetroAchievementsHost("media.retroachievements.org"))
        assertFalse(isRetroAchievementsHost("retroachievements.org.evil.com"))
        assertFalse(isRetroAchievementsHost("evil-retroachievements.org.attacker.net"))
        assertFalse(isRetroAchievementsHost("example.com"))
        assertFalse(isRetroAchievementsHost(null))
        assertFalse(isRetroAchievementsHost(""))
    }

    @Test
    fun httpStatusClassificationPreservesRejectionAndNotFound() {
        assertEquals(
            RaHttpResult.Body("ok"),
            classifyRetroAchievementsResponse(200) { "ok" },
        )
        assertEquals(RaHttpResult.Rejected, classifyRetroAchievementsResponse(401) { error("must not read") })
        // A 403 is a refusal of the key only when its body is RA's own JSON error; with no body it is a block in front of RA (see RetroAchievementsRateLimitTest).
        assertEquals(RaHttpResult.Unreachable, classifyRetroAchievementsResponse(403) { error("must not read") })
        assertEquals(
            "credential-bearing requests must not treat a redirect as a response body",
            RaHttpResult.Unreachable,
            classifyRetroAchievementsResponse(302) { error("must not read") },
        )
        assertEquals(RaHttpResult.NotFound, classifyRetroAchievementsResponse(404) { error("must not read") })
        assertEquals(RaHttpResult.RateLimited(null), classifyRetroAchievementsResponse(429) { error("must not read") })
        assertEquals(RaHttpResult.Unreachable, classifyRetroAchievementsResponse(500) { error("must not read") })
        assertEquals(
            "a 2xx whose body cannot be read is still unusable",
            RaHttpResult.Unreachable,
            classifyRetroAchievementsResponse(204) { error("no body") },
        )
    }

    @Test
    fun progressParsesThroughTheSource() = runBlocking {
        val result = source().progressFor(77)
        assertTrue(result is RaProgressLookup.Read)
        val progress = (result as RaProgressLookup.Read).progress
        assertEquals(1, progress.unlockedCount)
        assertEquals(1, progress.totalCount)
    }
}
