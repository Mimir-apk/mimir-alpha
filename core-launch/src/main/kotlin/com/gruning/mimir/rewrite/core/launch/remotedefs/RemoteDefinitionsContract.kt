package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.security.MessageDigest

/*
 * THROWAWAY PROTOTYPE (branch proto/remote-emulator-definitions, never merged to the alpha).
 *
 * An opt-in, user-started overlay of emulator launch DETAILS (alternate activity names, RetroArch cores,
 * recognition rules) for packages Mimir already bundles and queries. Pure Kotlin/JVM: no Android types, no
 * network call in this package except through the injected [RemoteDefinitionsTransport] seam, which has the
 * same shape as the app's MimirProviderEgressOpener. Transport is injected for testability.
 */

/** Every limit the validator enforces, in one place so the matrix tests can probe each edge. */
object RemoteDefinitionsLimits {
    /** The newest envelope schema this build understands. A newer file is refused, never half-read. */
    const val SUPPORTED_SCHEMA: Int = 1

    /** Compressed and decoded body cap (note section 5.7). */
    const val MAX_BYTES: Int = 128 * 1024
    const val MAX_ROWS: Int = 256
    const val MAX_STRING_BYTES: Int = 256
    const val MAX_REGEX_CHARS: Int = 100
    const val MAX_DEPTH: Int = 8
    const val MAX_KEYS_PER_OBJECT: Int = 16
    const val MAX_EXTENSIONS_PER_ROW: Int = 16
    const val MAX_ALTERNATES_PER_PACKAGE_PLATFORM: Int = 4
    const val MAX_REDIRECTS: Int = 2
    const val CONNECT_TIMEOUT_MS: Int = 10_000
    const val READ_TIMEOUT_MS: Int = 15_000

    /** The largest ordinal anything accepts. The tag grammar `defs-<ordinal>` is built from this same number. */
    const val MAX_ORDINAL: Long = 999_999_999L

    /** A pointer may name at most this many releases beyond what the app holds (else a typo could never be undone). */
    const val MAX_ORDINAL_JUMP: Long = 100L

    /** One player-started check, pointer and release together, ends within this many milliseconds. */
    const val CHECK_BUDGET_MS: Long = 30_000L
}

/** Why a candidate was refused. Whole file or nothing: no code means "partly applied". */
enum class RejectCode {
    SIZE,
    TRUNCATED,
    REDIRECT,
    HTTP_STATUS,
    ENCODING,
    BOM,
    NOT_JSON,
    PARSE,
    DUPLICATE_KEY,
    TOO_DEEP,
    SCHEMA_NEWER,
    BAD_SCHEMA,
    BAD_ENVELOPE,
    UNKNOWN_KEY,
    FORBIDDEN_FIELD,
    HASH_MISMATCH,
    PIN_MISMATCH,
    BAD_PIN,
    ROLLBACK,
    ORDINAL_REUSE,
    ORDINAL_JUMP,
    NEEDS_NEWER_APP,
    TOO_MANY_ROWS,
    BAD_ROW_TYPE,
    DUPLICATE_ROW,
    UNKNOWN_PACKAGE,
    BAD_PLATFORM,
    PLATFORM_NOT_SERVED,
    NOT_DISPATCHABLE,
    BAD_CLASS_NAME,
    BAD_CORE,
    BAD_EXTENSION,
    BAD_REGEX,
    BAD_STRING,
    STRING_TOO_LONG,
    CONFLICT,
    STORE_IO,
    INTERNAL,
}

/** Typed result of one user-started refresh (note I10). Never carries a URL. */
sealed interface RefreshResult {
    /** The received content is exactly what is already stored: nothing was written or emitted. */
    data object NoChange : RefreshResult

    data class Applied(val ordinal: Long, val rawSha256: String) : RefreshResult

    data class Rejected(val code: RejectCode) : RefreshResult

    /** No usable answer (no connection, timeout, 429, 5xx). Last good kept. */
    data class Offline(val detail: String) : RefreshResult

    /** Another refresh on this refresher is still running. */
    data object SkippedInFlight : RefreshResult

    /** The opt-in switch is off: nothing left the process. */
    data object NotEnabled : RefreshResult
}

internal class OverlayRejected(val code: RejectCode, detail: String) : RuntimeException("$code: $detail")

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
