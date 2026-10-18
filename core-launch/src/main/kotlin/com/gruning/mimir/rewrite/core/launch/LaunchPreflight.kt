package com.gruning.mimir.rewrite.core.launch

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.resolver.M3uPlaylistResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Checks that run on the locator BEFORE any launch intent is built. They exist so a launch Mimir can see is
 * doomed says so in words, instead of handing the emulator a game it can only fail on.
 *
 * Two rules live here, both pure over their inputs (every Android fact comes in through a lambda):
 *  - [MelonDsRomFolderRule] (finding F8): an old melonDS cannot open a game outside the folder it scanned.
 *  - [PlaylistPreflight] (finding F4): an `.m3u` or `.cue` that names a file that is not there.
 *
 * A refusal is a typed [com.gruning.mimir.rewrite.core.model.LaunchOutcome.Failed] built by the dispatcher,
 * so it is never an intent, never counts as a launch, and reaches the card through the same path as every
 * other failed dispatch. Both rules FAIL OPEN: anything they cannot read or cannot prove (a version Mimir
 * cannot see, a playlist it cannot open, a member whose folder it has no access to) lets the launch go.
 */
data class LaunchPreflightRefusal(
    val failure: LaunchDispatchFailure,
    /** The log vocabulary, in the same register as the dispatcher's other `reason` strings. */
    val reason: String,
    /** The one plain-English sentence the card shows. */
    val detail: String,
)

/** The SAF authority Mimir's tree scans and melonDS's own scans both live under. */
internal const val ExternalStorageAuthorityPrefix = "content://com.android.externalstorage.documents/"

/**
 * F8 (cert 2026-10-08): melonDS resolves the `uri` of LAUNCH_ROM against the ROMs it scanned itself.
 *
 * Source read with `gh api` on rafaelvcaetano/melonDS-android:
 *  - tag `beta-1.10.0` is versionCode **33**. Its `FileSystemRomsRepository.getRomAtUri` is
 *    `getRoms().first().find { it.uri == uri }`: an exact match against its own scan, and a miss shows
 *    "Could not find ROM". Mimir's own provider URI (`content://<package>.launchfiles/...`) can never match.
 *  - commit 7ce5ce5ba (2025-10-11) makes `getRomAtUri` fall back to building a ROM from an unknown URI
 *    ("ROM is not known. Create a new ROM from the URI"). It is an ancestor of every build from
 *    versionCode 35 on; versionCode 34 (2025-03-17 to 2025-11-03) straddles it.
 *  - tag `2.0.0` is versionCode **39** and `2.0.1` is **40**; neither is the first with the fallback.
 *
 * So a build is known to lack the fallback only at versionCode 33 or lower. A cutoff below the 2.0 versionCode 39
 * would also refuse versionCode 34 to 38, builds that open any URI; the
 * tested install reads versionCode 37. This rule therefore refuses only what the source proves, and
 * [LAST_VERSION_CODE_WITHOUT_URI_FALLBACK] is the one number to raise to 38 if the stricter reading is wanted.
 *
 * DETECTION, not a URI change: no URI Mimir can build matches a folder melonDS has not scanned. A SAF
 * document URI is let through because it is the shape melonDS's own tree scan produces; whether it names
 * the same tree melonDS scanned is not something Mimir can see. Only `me.magnum.melonds` is gated. Other
 * melonDS builds (a `.nightly` package, `me.magnum.melondualds`) are not: Mimir has no source reading of which of
 * their version numbers lack the fallback, and a rule it cannot prove would refuse games that open fine.
 */
object MelonDsRomFolderRule {
    const val PACKAGE_NAME = "me.magnum.melonds"

    /** versionCode of Beta 1.10.0, the newest release whose ROM lookup is an exact match only. */
    const val LAST_VERSION_CODE_WITHOUT_URI_FALLBACK = 33L

    /** versionCode of 2.0.0 (informational: the first tagged release the plain-English copy points to). */
    const val VERSION_CODE_2_0_0 = 39L

    internal const val Reason = "emulator_needs_own_rom_folder"

    /** The version is not named: the rule reads a versionCode, and 1.10 builds straddle the fallback (see the KDoc). */
    const val Copy =
        "This melonDS version can only open games from its own ROM folder. " +
            "Update melonDS, or add this folder in melonDS."

    /** True for the `.../document/...` shape melonDS's own tree scan produces. */
    fun isSafDocumentUri(locator: String): Boolean =
        locator.startsWith(ExternalStorageAuthorityPrefix) && "/document/" in locator

    fun refusal(
        packageName: String,
        installedVersionCode: Long?,
        locator: String,
    ): LaunchPreflightRefusal? {
        if (packageName != PACKAGE_NAME) return null
        // A version Mimir cannot read is not evidence of an old one.
        val version = installedVersionCode ?: return null
        if (version > LAST_VERSION_CODE_WITHOUT_URI_FALLBACK) return null
        if (isSafDocumentUri(locator)) return null
        return LaunchPreflightRefusal(LaunchDispatchFailure.EMULATOR_NEEDS_OWN_ROM_FOLDER, Reason, Copy)
    }
}

/** Whether a playlist member is provably absent. Anything uncertain answers false. */
class PlaylistFileOps(
    /** UTF-8 text of a playlist (path or `content://`), at most `maxBytes` bytes cut on a line, or null if unreadable. */
    val readText: (location: String, maxBytes: Long) -> String?,
    /** True only when [location] is positively gone: ENOENT/ENOTDIR for a path, "no such document" for a URI. */
    val definitelyMissing: (location: String) -> Boolean,
)

/**
 * F4 (cert 2026-10-08): an `.m3u` or `.cue` was launched with no look at the files it names.
 *
 * `.m3u` lines and `.cue` `FILE` entries are resolved against the playlist's own folder, the way the
 * emulator will, and the FIRST one that is positively missing refuses the launch ("Disc 2 is missing:
 * name", or "Track file is missing: name"). The limits are the scan's: [M3uPlaylistResolver.MAX_PLAYLIST_BYTES]
 * (64 KiB) and its 128 entries, through its own parser, so a playlist the scan splits is checked on exactly
 * the entries the scan saw. Cost is one bounded read of the playlist and one `stat` per entry; it runs on
 * the launch's IO thread (`LaunchController.launch` is `withContext(ioDispatcher)`), never the main thread.
 *
 * Playlist entries may be a path next to the playlist, an absolute path (Mimir's own generated playlists),
 * a `file://` path, or a `content://` document. The playlist itself may be a path or an ExternalStorage
 * SAF document; a member beside a SAF document is the sibling document in the same tree. A `content://`
 * member, or a playlist under any other provider, cannot be placed and is skipped. A Windows drive path
 * (`C:\...`) in a sheet is skipped as well: it was written on a PC and cannot be looked up here.
 *
 * An `.m3u` whose discs are themselves `.cue` sheets has those sheets' tracks checked too, one level deep
 * and for at most [MAX_NESTED_CUES] sheets, in list order: the first thing missing, disc or track, is the
 * one named. A playlist inside a playlist is not opened.
 */
object PlaylistPreflight {
    const val MAX_PLAYLIST_BYTES: Long = M3uPlaylistResolver.MAX_PLAYLIST_BYTES

    /** The scan's cap (`M3uPlaylistResolver` keeps the number private); a test pins them equal. */
    const val MAX_ENTRIES = 128

    internal const val MissingDiscReason = "playlist_disc_missing"
    internal const val MissingTrackReason = "cue_track_file_missing"
    private const val MAX_SHOWN_NAME = 96

    /** How many `.cue` members of an `.m3u` are opened to check their tracks: one level deep, and cheap. */
    internal const val MAX_NESTED_CUES = 16

    /** A Windows path (for example `C:/Games/a.bin`): the sheet was written on a PC and means nothing on this device. */
    private val DrivePath = Regex("""^[A-Za-z]:[\\/].*""")

    private val CueFileLine = Regex("""^\s*FILE\s+(?:"([^"]*)"|(\S+))\s+\S+\s*$""", RegexOption.IGNORE_CASE)

    private enum class Kind { M3U, CUE }

    fun refusal(locator: String, ops: PlaylistFileOps): LaunchPreflightRefusal? {
        val kind = kindOf(locator) ?: return null
        val body = ops.readText(locator, MAX_PLAYLIST_BYTES) ?: return null
        if (kind == Kind.CUE) return missingTrack(locator, body, ops)
        var cuesOpened = 0
        M3uPlaylistResolver.parseEntries(body).forEachIndexed { index, entry ->
            val member = memberLocation(locator, entry) ?: return@forEachIndexed
            if (ops.definitelyMissing(member)) {
                return LaunchPreflightRefusal(
                    LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING,
                    MissingDiscReason,
                    "Disc ${index + 1} is missing: ${shownName(entry)}",
                )
            }
            // A disc that is itself a cue sheet names track files: check those too, one level deep.
            if (cuesOpened < MAX_NESTED_CUES && kindOf(member) == Kind.CUE) {
                cuesOpened++
                val cueBody = ops.readText(member, MAX_PLAYLIST_BYTES) ?: return@forEachIndexed
                missingTrack(member, cueBody, ops)?.let { return it }
            }
        }
        return null
    }

    /** The first `FILE` of a cue sheet, at [cueLocation], that is positively missing beside it. */
    private fun missingTrack(cueLocation: String, body: String, ops: PlaylistFileOps): LaunchPreflightRefusal? {
        for (entry in cueFileNames(body)) {
            val member = memberLocation(cueLocation, entry) ?: continue
            if (!ops.definitelyMissing(member)) continue
            return LaunchPreflightRefusal(
                LaunchDispatchFailure.PLAYLIST_MEMBER_MISSING,
                MissingTrackReason,
                "Track file is missing: ${shownName(entry)}",
            )
        }
        return null
    }

    private fun kindOf(locator: String): Kind? {
        val name = locator.substringBefore('#').substringBefore('?').substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".m3u") || name.endsWith(".m3u8") -> Kind.M3U
            name.endsWith(".cue") -> Kind.CUE
            else -> null
        }
    }

    /** The `FILE` names of a cue sheet, in order, at most [MAX_ENTRIES]. */
    internal fun cueFileNames(body: String): List<String> = body
        .removePrefix("﻿")
        .lineSequence()
        .mapNotNull { line -> CueFileLine.matchEntire(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } }
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .take(MAX_ENTRIES)
        .toList()

    /** Where [entry] lives, as a location [PlaylistFileOps] can ask about, or null when it cannot be placed. */
    internal fun memberLocation(playlistLocator: String, entry: String): String? = when {
        DrivePath.matches(entry) -> null
        entry.startsWith("content://") -> entry
        entry.startsWith("file://") -> entry.removePrefix("file://").takeIf { it.startsWith('/') }
        entry.startsWith('/') -> entry
        else -> M3uPlaylistResolver.normalizeRelative(entry)?.let { siblingLocation(playlistLocator, it) }
    }

    /** [relative] beside [playlistLocator], in the same folder or the same SAF tree. */
    internal fun siblingLocation(playlistLocator: String, relative: String): String? {
        if (!playlistLocator.startsWith("content://")) {
            val parent = playlistLocator.substringBeforeLast('/', missingDelimiterValue = "")
            return if (parent.isEmpty()) null else "$parent/$relative"
        }
        if (!playlistLocator.startsWith(ExternalStorageAuthorityPrefix)) return null
        val withoutQuery = playlistLocator.substringBefore('#').substringBefore('?')
        val marker = withoutQuery.indexOf("/document/")
        if (marker < 0) return null
        val prefix = withoutQuery.substring(0, marker + "/document/".length)
        val documentId = percentDecode(withoutQuery.substring(prefix.length).substringBefore('/')) ?: return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val folder = documentId.substringBeforeLast('/', missingDelimiterValue = documentId.substring(0, colon + 1))
        val siblingId = if (folder.endsWith(":")) "$folder$relative" else "$folder/$relative"
        return prefix + percentEncode(siblingId)
    }

    private fun shownName(entry: String): String {
        val decoded = if (entry.startsWith("content://")) {
            percentDecode(entry.substringBefore('#').substringBefore('?')) ?: entry
        } else {
            entry
        }
        val name = decoded
            .replace('\\', '/')
            .trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
        return if (name.length <= MAX_SHOWN_NAME) name else name.take(MAX_SHOWN_NAME - 1) + "…"
    }

    internal fun percentDecode(encoded: String): String? =
        runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()

    /** `Uri.encode`'s alphabet: letters, digits and `_-!.~'()*` stay, every other UTF-8 byte becomes `%XX`. */
    internal fun percentEncode(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "_-!.~'()*") {
                out.append(char)
            } else {
                out.append('%').append("0123456789ABCDEF"[value shr 4]).append("0123456789ABCDEF"[value and 0xF])
            }
        }
        return out.toString()
    }

    /**
     * Reads at most [maxBytes] of [input] as UTF-8. A file longer than the cap is cut back to its last whole line,
     * so a half-read file name can never read as a missing file.
     */
    fun readBoundedText(input: InputStream, maxBytes: Long): String {
        val buffer = ByteArray((maxBytes + 1).toInt())
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        if (read <= maxBytes) return String(buffer, 0, read, Charsets.UTF_8)
        val text = String(buffer, 0, maxBytes.toInt(), Charsets.UTF_8)
        return text.substring(0, text.lastIndexOf('\n').coerceAtLeast(0))
    }
}

/** The launch-time entry point: both rules, in the order a player would want the answer. */
object LaunchPreflight {
    fun refusalFor(
        target: LaunchTarget,
        installedVersionCode: (packageName: String) -> Long?,
        playlistOps: PlaylistFileOps,
    ): LaunchPreflightRefusal? {
        if (target.locatorForm == LaunchLocatorForm.NONE || target.locator.isBlank()) return null
        // GameNative's locator is a shortcut file whose content is the payload, not a ROM.
        if (GameNativeLaunchRoute.isTarget(target.emulator.packageName)) return null
        val packageName = target.emulator.packageName
        if (packageName == MelonDsRomFolderRule.PACKAGE_NAME) {
            MelonDsRomFolderRule.refusal(packageName, installedVersionCode(packageName), target.locator)
                ?.let { return it }
        }
        return runCatching { PlaylistPreflight.refusal(target.locator, playlistOps) }.getOrNull()
    }
}

/**
 * The real [PlaylistFileOps]: a path is read with `java.io` and asked about with [pathDefinitelyMissing] (the
 * dispatcher's own ENOENT/ENOTDIR-only stat, so ACCESS_DENIED stays "unknown"); an ExternalStorage SAF
 * document is read through the [ContentResolver][android.content.ContentResolver] and asked about with a
 * document query, where only a `FileNotFoundException` means gone.
 */
fun androidPlaylistFileOps(context: Context, pathDefinitelyMissing: (String) -> Boolean): PlaylistFileOps {
    val resolver = context.applicationContext.contentResolver
    return PlaylistFileOps(
        readText = { location, maxBytes ->
            runCatching {
                val stream = if (location.startsWith("content://")) {
                    resolver.openInputStream(Uri.parse(location))
                } else {
                    File(location).inputStream()
                }
                stream?.use { PlaylistPreflight.readBoundedText(it, maxBytes) }
            }.getOrNull()
        },
        definitelyMissing = { location ->
            when {
                location.startsWith(ExternalStorageAuthorityPrefix) -> try {
                    resolver.query(
                        Uri.parse(location),
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null,
                        null,
                        null,
                    )?.use { it.count == 0 } == true
                } catch (_: FileNotFoundException) {
                    true
                } catch (_: Exception) {
                    false
                }
                location.startsWith("content://") -> false
                else -> pathDefinitelyMissing(location)
            }
        },
    )
}
