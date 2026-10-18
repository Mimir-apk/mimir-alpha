package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.metadata.online.RaValidation
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import com.gruning.mimir.rewrite.feature.settings.clearRetroAchievementsConnectDraft
import com.gruning.mimir.rewrite.feature.settings.retroAchievementsConnectMayClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Serial app-layer owner of the live-validated RetroAchievements connect flow. */
internal class RetroAchievementsConnectController(
    private val scope: CoroutineScope,
    private val beginAttempt: () -> Long,
    private val validate: suspend (username: String, apiKey: String) -> RaValidation,
    private val persistAccepted:
        (token: Long, username: String, apiKey: String) -> RetroAchievementsCredentialCommitResult?,
    private val publishIfCurrent: (token: Long, publish: () -> Unit) -> Boolean,
    private val cancelAttempt: (Long) -> Boolean,
    private val recoverUncertain: () -> Boolean,
    private val onAcceptedClosed: () -> Unit,
    private val clearDraft: () -> Unit = ::clearRetroAchievementsConnectDraft,
) {
    private var activeAttempt: Long? = null

    var state by mutableStateOf<RetroAchievementsConnectUi?>(null)
        private set

    val isOpen: Boolean get() = state != null
    val isValidating: Boolean get() = state is RetroAchievementsConnectUi.Validating

    fun open(currentUsername: String?) {
        if (state == null) {
            state = RetroAchievementsConnectUi.EnterUsername(currentUsername.orEmpty())
        }
    }

    fun usernameEntered(username: String) {
        val trimmed = username.trim()
        if (trimmed.isNotBlank() && !isValidating) {
            state = RetroAchievementsConnectUi.EnterKey(trimmed)
        }
    }

    fun submit(username: String, apiKey: String) {
        if (isValidating) return
        val trimmedUser = username.trim()
        val trimmedKey = apiKey.trim()
        if (trimmedUser.isBlank()) {
            state = RetroAchievementsConnectUi.EnterUsername(trimmedUser)
            return
        }
        if (trimmedKey.isBlank()) {
            state = RetroAchievementsConnectUi.EnterKey(trimmedUser)
            return
        }

        state = RetroAchievementsConnectUi.Validating(trimmedUser)
        val attempt = beginAttempt()
        activeAttempt = attempt
        scope.launch {
            try {
                val result = validateAndPersistRetroAchievements(
                    username = trimmedUser,
                    apiKey = trimmedKey,
                    validate = validate,
                    persistAccepted = { user, key ->
                        persistAccepted(attempt, user, key)
                    },
                )
                currentCoroutineContext().ensureActive()
                // A repeated username is not an operation identity. Publish only for this exact generation.
                if (
                    activeAttempt == attempt &&
                    state == RetroAchievementsConnectUi.Validating(trimmedUser)
                ) {
                    publishIfCurrent(attempt) {
                        if (
                            activeAttempt == attempt &&
                            state == RetroAchievementsConnectUi.Validating(trimmedUser)
                        ) {
                            clearDraft()
                            state = result
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelAttempt(attempt)) clearDraft()
                throw cancelled
            } finally {
                if (activeAttempt == attempt) {
                    activeAttempt = null
                }
            }
        }
    }

    fun retry() {
        if (state is RetroAchievementsConnectUi.RecoveryUncertain && !recoverUncertain()) return
        state = retryRetroAchievementsConnectState(state) ?: state
    }

    /** False means validation or unresolved durable recovery owns the modal and Back/scrim is swallowed. */
    fun dismiss(): Boolean {
        val current = state ?: return true
        if (!retroAchievementsConnectMayClose(current)) return false
        activeAttempt?.let { attempt ->
            if (cancelAttempt(attempt)) clearDraft()
        }
        activeAttempt = null
        if (current !is RetroAchievementsConnectUi.Validating) clearDraft()
        state = null
        if (current is RetroAchievementsConnectUi.Accepted) onAcceptedClosed()
        return true
    }
}

/**
 * One candidate transaction. Neither consent nor ciphertext is written before both fields are accepted;
 * failures return a UI state containing no key material.
 */
internal suspend fun validateAndPersistRetroAchievements(
    username: String,
    apiKey: String,
    validate: suspend (username: String, apiKey: String) -> RaValidation,
    persistAccepted:
        (username: String, apiKey: String) -> RetroAchievementsCredentialCommitResult?,
): RetroAchievementsConnectUi {
    val trimmedUser = username.trim()
    val trimmedKey = apiKey.trim()
    val validation = try {
        validate(trimmedUser, trimmedKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RaValidation.Unreachable
    }

    return when (validation) {
        RaValidation.Accepted -> {
            currentCoroutineContext().ensureActive()
            val saved = try {
                persistAccepted(trimmedUser, trimmedKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            when (saved) {
                RetroAchievementsCredentialCommitResult.Activated ->
                    RetroAchievementsConnectUi.Accepted(trimmedUser)
                RetroAchievementsCredentialCommitResult.FailedRestored ->
                    RetroAchievementsConnectUi.SaveFailed(trimmedUser)
                RetroAchievementsCredentialCommitResult.FailedUncertain,
                null,
                -> RetroAchievementsConnectUi.RecoveryUncertain(trimmedUser)
            }
        }

        RaValidation.KeyRejected -> RetroAchievementsConnectUi.Rejected(
            trimmedUser,
            RetroAchievementsConnectUi.RejectedField.API_KEY,
        )

        RaValidation.UsernameRejected -> RetroAchievementsConnectUi.Rejected(
            trimmedUser,
            RetroAchievementsConnectUi.RejectedField.USERNAME,
        )

        RaValidation.Unreachable -> RetroAchievementsConnectUi.Unreachable(trimmedUser)
    }
}

internal fun retryRetroAchievementsConnectState(
    state: RetroAchievementsConnectUi?,
): RetroAchievementsConnectUi? = when (state) {
    is RetroAchievementsConnectUi.Rejected -> if (state.field == RetroAchievementsConnectUi.RejectedField.USERNAME) {
        RetroAchievementsConnectUi.EnterUsername(state.username)
    } else {
        RetroAchievementsConnectUi.EnterKey(state.username)
    }
    is RetroAchievementsConnectUi.Unreachable -> RetroAchievementsConnectUi.EnterKey(state.username)
    is RetroAchievementsConnectUi.SaveFailed -> RetroAchievementsConnectUi.EnterKey(state.username)
    is RetroAchievementsConnectUi.RecoveryUncertain -> RetroAchievementsConnectUi.EnterKey(state.username)
    else -> null
}
