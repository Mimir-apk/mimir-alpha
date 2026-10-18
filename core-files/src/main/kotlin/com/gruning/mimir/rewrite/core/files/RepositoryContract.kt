package com.gruning.mimir.rewrite.core.files

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.model`. Upstream path:
 * `core/model/src/main/java/com/explorer/fileexplorer/core/model/RepositoryContract.kt`.
 *
 * Modified per Mimir's no-network-on-scan/launch invariant — symbols stripped, not merely
 * disabled:
 *  - [RepositoryOperation]: removed CONNECT, DISCONNECT, DOWNLOAD, UPLOAD, AUTHENTICATE,
 *    REFRESH_TOKEN, SIGN_OUT, CREATE_FOLDER, QUOTA (remote/cloud-only operations).
 *  - `RepositoryCapabilities.network(...)` and `RepositoryCapabilities.cloud(...)` factories
 *    removed.
 *  - `httpRepositoryException(...)` removed (HTTP-status-code error mapping).
 *
 * All removed symbols had zero production callers in the modules Mimir is taking (core/storage,
 * core/data, feature/browser) — verified by grep against the upstream tree; their only callers
 * were the excluded core/network, core/cloud, feature/network, feature/cloud, feature/transfer
 * modules and this module's own upstream unit tests (core/data/src/test).
 */

/** Operations that a file-backed provider can expose. */
enum class RepositoryOperation {
    LIST,
    INFO,
    EXISTS,
    COPY,
    MOVE,
    DELETE,
    CREATE_DIRECTORY,
    CREATE_FILE,
    RENAME,
    SIZE,
    SEARCH,
    CHECKSUM,
}

/** Stable categories used by UI, automation, and diagnostics. */
enum class RepositoryErrorKind {
    UNSUPPORTED,
    AUTHENTICATION,
    PERMISSION,
    TRANSPORT,
    CONFLICT,
    CORRUPT,
    CANCELLED,
    NOT_FOUND,
    INVALID,
    STORAGE,
    UNKNOWN,
}

data class RepositoryError(
    val provider: String,
    val operation: RepositoryOperation,
    val kind: RepositoryErrorKind,
    val message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
)

/** Exception form of [RepositoryError] for legacy nullable and Flow contracts. */
class RepositoryException(
    val error: RepositoryError,
    cause: Throwable? = null,
) : IOException(error.message, cause)

enum class RepositoryConsistency {
    STRONG,
    POINT_IN_TIME,
    BEST_EFFORT,
    UNKNOWN,
}

enum class RepositoryOperationCost {
    LOW,
    MODERATE,
    HIGH,
}

data class RepositoryOperationSemantics(
    val consistency: RepositoryConsistency,
    val cost: RepositoryOperationCost,
    val cancellable: Boolean,
    val limit: String? = null,
)

data class RepositoryCapabilities(
    val provider: String,
    val supportedOperations: Set<RepositoryOperation>,
    val operationSemantics: Map<RepositoryOperation, RepositoryOperationSemantics> = emptyMap(),
    val kind: RepositoryProviderKind = RepositoryProviderKind.UNKNOWN,
) {
    fun supports(operation: RepositoryOperation): Boolean = operation in supportedOperations

    fun semantics(operation: RepositoryOperation): RepositoryOperationSemantics? =
        operationSemantics[operation]

    companion object {
        val FILE_OPERATIONS: Set<RepositoryOperation> = setOf(
            RepositoryOperation.LIST,
            RepositoryOperation.INFO,
            RepositoryOperation.EXISTS,
            RepositoryOperation.COPY,
            RepositoryOperation.MOVE,
            RepositoryOperation.DELETE,
            RepositoryOperation.CREATE_DIRECTORY,
            RepositoryOperation.CREATE_FILE,
            RepositoryOperation.RENAME,
            RepositoryOperation.SIZE,
            RepositoryOperation.SEARCH,
            RepositoryOperation.CHECKSUM,
        )

        fun local(
            provider: String,
            kind: RepositoryProviderKind = RepositoryProviderKind.LOCAL,
        ): RepositoryCapabilities =
            RepositoryCapabilities(
                provider = provider,
                supportedOperations = FILE_OPERATIONS,
                operationSemantics = FILE_OPERATIONS.associateWith { operation ->
                    RepositoryOperationSemantics(
                        consistency = RepositoryConsistency.STRONG,
                        cost = if (operation == RepositoryOperation.SIZE ||
                            operation == RepositoryOperation.SEARCH ||
                            operation == RepositoryOperation.CHECKSUM
                        ) RepositoryOperationCost.MODERATE else RepositoryOperationCost.LOW,
                        cancellable = true,
                    )
                },
                kind = kind,
            )

        fun unsupported(provider: String): RepositoryCapabilities =
            RepositoryCapabilities(
                provider = provider,
                supportedOperations = emptySet(),
                kind = RepositoryProviderKind.UNKNOWN,
            )
    }
}

fun repositoryException(
    provider: String,
    operation: RepositoryOperation,
    kind: RepositoryErrorKind,
    message: String,
    retryable: Boolean,
    statusCode: Int? = null,
    cause: Throwable? = null,
): RepositoryException = RepositoryException(
    error = RepositoryError(
        provider = provider,
        operation = operation,
        kind = kind,
        message = "$provider ${operation.name.lowercase()}: ${message.trim().take(MAX_ERROR_MESSAGE_LENGTH)}",
        retryable = retryable,
        statusCode = statusCode,
    ),
    cause = cause,
)

fun unsupportedRepositoryOperation(
    provider: String,
    operation: RepositoryOperation,
): RepositoryException = repositoryException(
    provider = provider,
    operation = operation,
    kind = RepositoryErrorKind.UNSUPPORTED,
    message = "operation is not supported",
    retryable = false,
)

fun notConnectedRepositoryException(
    provider: String,
    operation: RepositoryOperation,
): RepositoryException = repositoryException(
    provider = provider,
    operation = operation,
    kind = RepositoryErrorKind.TRANSPORT,
    message = "provider is not connected",
    retryable = true,
)

fun Throwable.asRepositoryException(
    provider: String,
    operation: RepositoryOperation,
    defaultKind: RepositoryErrorKind = RepositoryErrorKind.UNKNOWN,
): RepositoryException {
    if (this is CancellationException) throw this
    if (this is RepositoryException && error.provider == provider && error.operation == operation) return this

    val kind = when {
        this is AccessDeniedException || this is SecurityException -> RepositoryErrorKind.PERMISSION
        this is FileAlreadyExistsException -> RepositoryErrorKind.CONFLICT
        this is NoSuchFileException || this is FileNotFoundException -> RepositoryErrorKind.NOT_FOUND
        message.orEmpty().containsAnyIgnoreCase("authentication", "auth fail", "unauthorized", "login failed", "invalid credential") ->
            RepositoryErrorKind.AUTHENTICATION
        message.orEmpty().containsAnyIgnoreCase("permission denied", "access denied", "forbidden") ->
            RepositoryErrorKind.PERMISSION
        message.orEmpty().containsAnyIgnoreCase("malformed", "corrupt", "invalid json", "parse error") ->
            RepositoryErrorKind.CORRUPT
        message.orEmpty().containsAnyIgnoreCase("conflict", "already exists", "file exists") ->
            RepositoryErrorKind.CONFLICT
        defaultKind != RepositoryErrorKind.UNKNOWN -> defaultKind
        this is IOException -> RepositoryErrorKind.TRANSPORT
        this is IllegalArgumentException -> RepositoryErrorKind.INVALID
        else -> RepositoryErrorKind.UNKNOWN
    }
    return repositoryException(
        provider = provider,
        operation = operation,
        kind = kind,
        message = message ?: javaClass.simpleName,
        retryable = kind == RepositoryErrorKind.TRANSPORT,
        cause = this,
    )
}

fun Throwable.isMissingRepositoryResource(): Boolean {
    if (this is NoSuchFileException || this is FileNotFoundException) return true
    val text = message.orEmpty().lowercase()
    return "no such file" in text || "not found" in text || "does not exist" in text
}

fun <T> Result<T>.mapRepositoryFailure(
    provider: String,
    operation: RepositoryOperation,
): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { error -> Result.failure(error.asRepositoryException(provider, operation)) },
)

private const val MAX_ERROR_MESSAGE_LENGTH = 256

private fun String.containsAnyIgnoreCase(vararg values: String): Boolean {
    val normalized = lowercase()
    return values.any { it in normalized }
}
