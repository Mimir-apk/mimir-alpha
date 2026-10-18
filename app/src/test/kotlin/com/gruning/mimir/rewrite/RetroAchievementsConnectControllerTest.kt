package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaValidation
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroAchievementsConnectControllerTest {
    @Test
    fun `only a live acceptance writes consent and trimmed credentials`() = runBlocking {
        val events = mutableListOf<String>()
        val result = validateAndPersistRetroAchievements(
            username = "  player  ",
            apiKey = "  secret  ",
            validate = { user, key ->
                events += "validate:$user:$key"
                RaValidation.Accepted
            },
            persistAccepted = { user, key ->
                events += "save-and-opt-in:$user:$key"
                RetroAchievementsCredentialCommitResult.Activated
            },
        )

        assertEquals(RetroAchievementsConnectUi.Accepted("player"), result)
        assertEquals(listOf("validate:player:secret", "save-and-opt-in:player:secret"), events)
    }

    @Test
    fun `rejected fields stay distinct and nothing is saved`() = runBlocking {
        for ((validation, field) in listOf(
            RaValidation.KeyRejected to RetroAchievementsConnectUi.RejectedField.API_KEY,
            RaValidation.UsernameRejected to RetroAchievementsConnectUi.RejectedField.USERNAME,
        )) {
            var wrote = false
            val result = validateAndPersistRetroAchievements(
                username = "player",
                apiKey = "secret",
                validate = { _, _ -> validation },
                persistAccepted = { _, _ ->
                    wrote = true
                    RetroAchievementsCredentialCommitResult.Activated
                },
            )
            assertEquals(RetroAchievementsConnectUi.Rejected("player", field), result)
            assertFalse(wrote)
        }
    }

    @Test
    fun `unreachable is not called rejected and writes nothing`() = runBlocking {
        var wrote = false
        val result = validateAndPersistRetroAchievements(
            username = "player",
            apiKey = "secret",
            validate = { _, _ -> RaValidation.Unreachable },
            persistAccepted = { _, _ ->
                wrote = true
                RetroAchievementsCredentialCommitResult.Activated
            },
        )
        assertEquals(RetroAchievementsConnectUi.Unreachable("player"), result)
        assertFalse(wrote)
    }

    @Test
    fun `accepted response with a local persistence failure never claims connected`() = runBlocking {
        val result = validateAndPersistRetroAchievements(
            username = "player",
            apiKey = "secret",
            validate = { _, _ -> RaValidation.Accepted },
            persistAccepted = { _, _ -> RetroAchievementsCredentialCommitResult.FailedRestored },
        )
        assertEquals(RetroAchievementsConnectUi.SaveFailed("player"), result)
    }

    @Test
    fun `uncertain local recovery receives its own stop warning`() = runBlocking {
        val result = validateAndPersistRetroAchievements(
            username = "player",
            apiKey = "secret",
            validate = { _, _ -> RaValidation.Accepted },
            persistAccepted = { _, _ -> RetroAchievementsCredentialCommitResult.FailedUncertain },
        )
        assertEquals(RetroAchievementsConnectUi.RecoveryUncertain("player"), result)
    }

    @Test
    fun `persistence cancellation is never collapsed into uncertain storage`() = runBlocking {
        var cancellationPropagated = false
        try {
            validateAndPersistRetroAchievements(
                username = "player",
                apiKey = "secret",
                validate = { _, _ -> RaValidation.Accepted },
                persistAccepted = { _, _ -> throw CancellationException("cancel") },
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

    @Test
    fun `retry returns to the field that can change`() {
        assertEquals(
            RetroAchievementsConnectUi.EnterUsername("player"),
            retryRetroAchievementsConnectState(
                RetroAchievementsConnectUi.Rejected(
                    "player",
                    RetroAchievementsConnectUi.RejectedField.USERNAME,
                ),
            ),
        )
        assertEquals(
            RetroAchievementsConnectUi.EnterKey("player"),
            retryRetroAchievementsConnectState(
                RetroAchievementsConnectUi.Rejected(
                    "player",
                    RetroAchievementsConnectUi.RejectedField.API_KEY,
                ),
            ),
        )
        assertEquals(
            RetroAchievementsConnectUi.EnterKey("player"),
            retryRetroAchievementsConnectState(RetroAchievementsConnectUi.Unreachable("player")),
        )
        assertEquals(
            RetroAchievementsConnectUi.EnterKey("player"),
            retryRetroAchievementsConnectState(
                RetroAchievementsConnectUi.RecoveryUncertain("player"),
            ),
        )
        assertTrue(retryRetroAchievementsConnectState(RetroAchievementsConnectUi.Accepted("player")) == null)
    }

    @Test
    fun `every terminal outcome clears the plaintext draft`() = runBlocking {
        for (answer in listOf(
            RaValidation.Accepted,
            RaValidation.KeyRejected,
            RaValidation.UsernameRejected,
            RaValidation.Unreachable,
        )) {
            var clears = 0
            val authority = RetroAchievementsConnectionOperationAuthority()
            val controller = RetroAchievementsConnectController(
                scope = this,
                beginAttempt = authority::begin,
                validate = { _, _ -> answer },
                persistAccepted = { token, _, _ ->
                    authority.runIfCurrent(token) {
                        RetroAchievementsCredentialCommitResult.Activated
                    }
                },
                publishIfCurrent = { token, publish ->
                    authority.runIfCurrent(token) {
                        publish()
                        true
                    } ?: false
                },
                cancelAttempt = authority::cancelIfCurrent,
                recoverUncertain = { true },
                onAcceptedClosed = {},
                clearDraft = { clears++ },
            )
            controller.open("player")
            controller.usernameEntered("player")
            controller.submit("player", "secret")
            yield()
            assertFalse(controller.isValidating)
            assertEquals("terminal $answer", 1, clears)
        }
    }

    @Test
    fun `stale validation generation neither persists publishes nor clears a newer draft`() = runBlocking {
        val authority = RetroAchievementsConnectionOperationAuthority()
        val answer = CompletableDeferred<RaValidation>()
        var persistCalls = 0
        var clears = 0
        val controller = RetroAchievementsConnectController(
            scope = this,
            beginAttempt = authority::begin,
            validate = { _, _ -> answer.await() },
            persistAccepted = { token, _, _ ->
                authority.runIfCurrent(token) {
                    persistCalls += 1
                    RetroAchievementsCredentialCommitResult.Activated
                }
            },
            publishIfCurrent = { token, publish ->
                authority.runIfCurrent(token) {
                    publish()
                    true
                } ?: false
            },
            cancelAttempt = authority::cancelIfCurrent,
            recoverUncertain = { true },
            onAcceptedClosed = {},
            clearDraft = { clears++ },
        )

        controller.open("same-user")
        controller.usernameEntered("same-user")
        controller.submit("same-user", "old-key")
        yield()
        authority.begin()
        answer.complete(RaValidation.Accepted)
        yield()

        assertEquals(0, persistCalls)
        assertEquals(0, clears)
        assertEquals(RetroAchievementsConnectUi.Validating("same-user"), controller.state)
    }

    @Test
    fun `dismiss before validation clears the plaintext draft`() = runBlocking {
        var clears = 0
        val authority = RetroAchievementsConnectionOperationAuthority()
        val controller = RetroAchievementsConnectController(
            scope = this,
            beginAttempt = authority::begin,
            validate = { _, _ -> RaValidation.Accepted },
            persistAccepted = { token, _, _ ->
                authority.runIfCurrent(token) {
                    RetroAchievementsCredentialCommitResult.Activated
                }
            },
            publishIfCurrent = { token, publish ->
                authority.runIfCurrent(token) {
                    publish()
                    true
                } ?: false
            },
            cancelAttempt = authority::cancelIfCurrent,
            recoverUncertain = { true },
            onAcceptedClosed = {},
            clearDraft = { clears++ },
        )
        controller.open("player")
        assertTrue(controller.dismiss())
        assertEquals(1, clears)
    }

    @Test
    fun `scope cancellation invalidates the live attempt and clears its plaintext draft`() = runBlocking {
        val authority = RetroAchievementsConnectionOperationAuthority()
        val validation = CompletableDeferred<RaValidation>()
        val controllerJob = Job()
        var clears = 0
        val controller = RetroAchievementsConnectController(
            scope = CoroutineScope(coroutineContext + controllerJob),
            beginAttempt = authority::begin,
            validate = { _, _ -> validation.await() },
            persistAccepted = { _, _, _ -> RetroAchievementsCredentialCommitResult.Activated },
            publishIfCurrent = { token, publish ->
                authority.runIfCurrent(token) {
                    publish()
                    true
                } ?: false
            },
            cancelAttempt = authority::cancelIfCurrent,
            recoverUncertain = { true },
            onAcceptedClosed = {},
            clearDraft = { clears++ },
        )
        controller.open("player")
        controller.usernameEntered("player")
        controller.submit("player", "secret")
        yield()
        val activeToken = 1L
        assertTrue(authority.isCurrent(activeToken))

        controllerJob.cancel()
        controllerJob.join()

        assertFalse(authority.isCurrent(activeToken))
        assertEquals(1, clears)
    }

    @Test
    fun `uncertain recovery cannot leave warning state until durable cleanup succeeds`() = runBlocking {
        val authority = RetroAchievementsConnectionOperationAuthority()
        var cleanupSucceeds = false
        val controller = RetroAchievementsConnectController(
            scope = this,
            beginAttempt = authority::begin,
            validate = { _, _ -> RaValidation.Accepted },
            persistAccepted = { _, _, _ -> RetroAchievementsCredentialCommitResult.FailedUncertain },
            publishIfCurrent = { token, publish ->
                authority.runIfCurrent(token) {
                    publish()
                    true
                } ?: false
            },
            cancelAttempt = authority::cancelIfCurrent,
            recoverUncertain = { cleanupSucceeds },
            onAcceptedClosed = {},
        )
        controller.open("player")
        controller.usernameEntered("player")
        controller.submit("player", "secret")
        yield()
        assertEquals(RetroAchievementsConnectUi.RecoveryUncertain("player"), controller.state)
        assertFalse(controller.dismiss())
        assertEquals(RetroAchievementsConnectUi.RecoveryUncertain("player"), controller.state)

        controller.retry()
        assertEquals(RetroAchievementsConnectUi.RecoveryUncertain("player"), controller.state)
        cleanupSucceeds = true
        controller.retry()
        assertEquals(RetroAchievementsConnectUi.EnterKey("player"), controller.state)
    }

    @Test
    fun `disconnect failure copy never promises durable deletion`() {
        assertEquals(null, retroAchievementsDisconnectFailureMessage(cleared = true))
        val failed = retroAchievementsDisconnectFailureMessage(cleared = false).orEmpty()
        assertTrue(failed.contains("off for this session"))
        assertTrue(failed.contains("couldn’t finish forgetting"))
    }
}
