package com.gruning.mimir.rewrite.core.launch

import java.io.File
import java.io.IOException

/**
 * The GameNative (`app.gamenative`) launch contract — R-2b. Grounded by static analysis of the build
 * installed on the test device, with BOTH sides of the intent read and agreeing:
 *
 *  - **Producer** — `ShortcutUtilsKt.createPinnedShortcut` writes one shortcut FILE per game into
 *    Mimir's scanned "pcgames" folder: filename = sanitised title + the source's extension, body = the
 *    decimal `app_id` as UTF-8 text. Not JSON, not a key/value file — a title in the name, an integer in
 *    the body.
 *  - **Consumer** — `IntentLaunchManager.parseLaunchIntent` requires action
 *    `app.gamenative.LAUNCH_GAME`, `getIntExtra("app_id", -1)`, and `getStringExtra("game_source")`
 *    resolved against `GameSource.valueOf(...)` (so the extra rides `Enum.name()` exactly — "CUSTOM_GAME",
 *    never "CUSTOM"). Any mismatch is REJECTED silently: GameNative logs and returns null, never a visible
 *    error. Mimir must therefore validate before it ever fires this intent, not after.
 *
 * This object is deliberately pure (no `android.*` import): the file-read + validation path — including explicit failure for a malformed shortcut file —
 * is provable with a real [java.io.File] in a plain JVM unit test, not only an unrunnable instrumented
 * one. Only the actual `Intent` assembly ([IntentLaunchDispatcher]) needs Android.
 *
 * Public (not `internal`) so [parse] is the ONE seam for reading a PC-games shortcut's embedded Steam
 * app id outside `core-launch` too — R-23  reuses it from
 * `core-resolver`/`app` for metadata identification, not just launch. Do not fork a second parser.
 */
object GameNativeLaunchRoute {
    const val PACKAGE_NAME = "app.gamenative"
    const val ACTIVITY_NAME = "app.gamenative.MainActivity"
    const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"

    /**
     * `FrontendSyncManager.extensionFor(source)` — the authoritative extension -> `GameSource.name()`
     * mapping, a single `when` over the enum in the installed dex. NOT an inference.
     */
    private val ExtensionToGameSource: Map<String, String> = mapOf(
        "steam" to "STEAM",
        "epic" to "EPIC",
        "gog" to "GOG",
        "amazon" to "AMAZON",
        // NOT "CUSTOM". The dex's FRONTEND_SYNC_DIR_CUSTOM string names the sync DIRECTORY, a
        // different constant, which is what makes "CUSTOM" look right. The extra rides Enum.name(), so
        // "CUSTOM" would simply never match GameNative's own GameSource.CUSTOM_GAME.
        "pcgame" to "CUSTOM_GAME",
    )

    /** A legitimate body is a handful of decimal digits (+ maybe a BOM/newline); refuse anything absurd.
     *  Public (not `private`), like [parse]: [parseShortcut] callers reading bytes from a source other
     *  than a [File] (e.g. a SAF document) bound their own read against this same cap. */
    const val MAX_SHORTCUT_BYTES = 64L
    // Escape, never a literal invisible character in source: a literal U+FEFF here survives review
    // only by luck, and any future formatter/editor pass that strips invisible characters would
    // silently turn this into a no-op prefix while the suite below stays green.
    private const val Utf8Bom = "\uFEFF"

    /** The outcome of reading and validating one shortcut file end to end. */
    sealed interface ParseResult {
        /** A validated, launch-ready payload. */
        data class Payload(val appId: Int, val gameSource: String) : ParseResult

        /**
         * The file could not be READ at all (missing, or an I/O error). Distinct from [Malformed]:
         * GameNative manages these files and can delete/re-sync them underneath a scan (contract doc
         * §3), so a vanished shortcut is an anticipated race, not corrupt data.
         */
        data class Unreadable(val reason: String) : ParseResult

        /** The file was read fine, but its extension or body content can't produce a valid payload. */
        data class Malformed(val reason: String) : ParseResult
    }

    /** True when [packageName] is the GameNative route's target — the single dispatch key for R-2b. */
    fun isTarget(packageName: String): Boolean = packageName == PACKAGE_NAME

    /** Extension (no leading dot, case-insensitive) -> GameNative's `GameSource.name()`, or null. */
    fun gameSourceForExtension(extension: String): String? = ExtensionToGameSource[extension.lowercase()]

    /**
     * Parse the shortcut body into a validated, strictly positive `app_id`, or null when malformed
     * (empty, non-numeric, or <= 0 — GameNative's own consumer treats -1 as "absent"; 0 is not a real
     * Steam/Epic/GOG/Amazon/custom id either, so both are rejected the same defensive way).
     */
    fun parseAppId(body: String): Int? = body.removePrefix(Utf8Bom).trim().toIntOrNull()?.takeIf { it > 0 }

    /**
     * Parse [extension] and [body] into a validated payload: the extension rule, the same empty/oversize
     * caps [parse] enforces via [MAX_SHORTCUT_BYTES], and the BOM/trim/positive-int body parse. The pure
     * core [parse] delegates to after reading a [File] end to end -- also the ONE seam a caller that
     * already has bytes in hand (no [File]; R6-P1-04's SAF document read) parses through instead of
     * forking a second reader.
     */
    fun parseShortcut(extension: String, body: ByteArray): ParseResult {
        val gameSource = gameSourceForExtension(extension)
            ?: return ParseResult.Malformed("unrecognized_extension:$extension")
        if (body.isEmpty()) return ParseResult.Malformed("shortcut_empty")
        if (body.size > MAX_SHORTCUT_BYTES) return ParseResult.Malformed("shortcut_oversized")
        val appId = parseAppId(String(body, Charsets.UTF_8)) ?: return ParseResult.Malformed("app_id_invalid")
        return ParseResult.Payload(appId, gameSource)
    }

    /** Read and validate [file] end to end: extension recognition, existence, size cap, and body parse. */
    fun parse(file: File): ParseResult {
        val length = try {
            if (!file.isFile) return ParseResult.Unreadable("shortcut_missing")
            file.length()
        } catch (e: SecurityException) {
            return ParseResult.Unreadable("shortcut_unreadable")
        }
        if (length > MAX_SHORTCUT_BYTES) return ParseResult.Malformed("shortcut_oversized")
        val body = try {
            file.readBytes()
        } catch (e: IOException) {
            return ParseResult.Unreadable("shortcut_unreadable")
        }
        return parseShortcut(file.extension, body)
    }
}
