package com.gruning.mimir.tweaker

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * API v11: a game Mimir launches cannot take audio focus, so the music already playing carries on.
 *
 * **Why an app-op.** Device evidence (Nova, 2026-10-01): an emulator taking audio focus at launch makes
 * YouTube Music pause and never resume (`shouldResumeOnAudioFocusGain=false`). `TAKE_AUDIO_FOCUS` set to
 * `ignore` for the game's package makes that package's `requestAudioFocus` fail while its audio still plays.
 *
 * **The only commands this daemon will run for it are the three templates in [GameAudioFocusCommands],
 * each with ONE argument that has already matched the package grammar.** Nothing a caller supplies is any
 * other part of a command: the op name, the modes and the verbs are compile-time constants. That is what
 * keeps this inside the contract the API has kept since v6, that a privileged process takes no command,
 * path, key or value from an unprivileged one.
 *
 * **Fail closed.** A hold is reported only after `appops get` reads `ignore`. Anything else puts the package
 * straight back to `default` and reports a refusal; the failure state is the game keeping normal focus
 * behaviour, which is today's behaviour.
 *
 * **App-ops persist across reboot** (unlike cpufreq or GameManager state), so a held package is journalled
 * BEFORE the command runs and every release path issues `set default` and reads it back; a boot or a daemon
 * restart never merely forgets the record.
 *
 * **Which route runs it.** `PServerClient.executeChecked`, the same privileged transport the discovered
 * ceiling and the GameManager FPS limiter end at. [DefaultPServerBinderAttestor] refuses to use that
 * service unless it answers uid 0, gid 0, SELinux domain `u:r:pservice:s0` and `/system/bin/pservice`, and
 * `AppOpsService`'s shell command admits root. Whether the `pservice` SELinux domain may reach the appops
 * binder on a given device is DEVICE-UNCERTIFIED here: the readback is what proves it per call.
 */
internal object GameAudioFocusCommands {
    const val OP = "TAKE_AUDIO_FOCUS"

    /** `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$` -- the same grammar the FPS limiter and the service use. */
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length <= MAX_PACKAGE_LENGTH && PACKAGE_PATTERN.matches(packageName)

    /** Mimir's own packages, this daemon included. */
    fun isMimirPackage(packageName: String): Boolean =
        packageName == MIMIR_PACKAGE_ROOT || packageName.startsWith("$MIMIR_PACKAGE_ROOT.")

    /** `cmd appops set <pkg> TAKE_AUDIO_FOCUS ignore` */
    fun hold(packageName: String): String? = command(packageName, "set", "ignore")

    /** `cmd appops set <pkg> TAKE_AUDIO_FOCUS default` */
    fun release(packageName: String): String? = command(packageName, "set", "default")

    /** `cmd appops get <pkg> TAKE_AUDIO_FOCUS` */
    fun read(packageName: String): String? = command(packageName, "get", null)

    private fun command(packageName: String, verb: String, mode: String?): String? {
        if (!isValidPackageName(packageName)) return null
        val command = buildString {
            append("cmd appops ").append(verb).append(' ').append(packageName).append(' ').append(OP)
            if (mode != null) append(' ').append(mode)
        }
        return command.takeIf(::checkedPServerCommandFits)
    }

    private const val MAX_PACKAGE_LENGTH = 160
    private const val MIMIR_PACKAGE_ROOT = "com.gruning.mimir"
}

/** What `appops get <pkg> TAKE_AUDIO_FOCUS` said, reduced to the only distinctions this feature acts on. */
internal enum class TakeAudioFocusMode {
    /** The hold: the package's `requestAudioFocus` fails. */
    IGNORE,

    /** `default`, `allow`, `foreground` or no entry at all: the package may take focus as it always could. */
    NOT_IGNORED,
}

/**
 * Parses `appops get` output. Null means it could not be read as one of the two modes, and a caller must
 * treat null as "unconfirmed", never as either answer.
 *
 * The shapes (AOSP `AppOpsService` shell `get`, Android 13 and 14), one per line:
 * - `TAKE_AUDIO_FOCUS: <mode>[; time=...]` -- the package's own mode;
 * - `Uid mode: TAKE_AUDIO_FOCUS: <mode>` -- the uid's mode, printed only when it is not the default;
 * - `No operations.`, optionally followed by `Default mode: <mode>` -- nothing recorded, with the op's own default.
 *
 * Reading them: a uid mode of `ignore` is a hold, whatever the package says. Otherwise the package line decides. A
 * package `ignore` under an explicit non-ignore uid mode is a conflict this cannot resolve, so it is unconfirmed.
 * `Default mode:` counts only straight after `No operations.`, and only for a mode that is not ignore. Any other
 * line, an unknown mode, or an empty reply is null: unknown shapes fail closed.
 * DEVICE-UNCERTIFIED: these shapes are from AOSP source, not from a Thor capture.
 */
internal fun parseTakeAudioFocusMode(output: String?): TakeAudioFocusMode? {
    val lines = output?.replace("\r\n", "\n")?.trim().orEmpty().lines().map(String::trim).filter(String::isNotEmpty)
    if (lines.isEmpty()) return null
    val uidModes = mutableListOf<String>()
    val packageModes = mutableListOf<String>()
    var noOperations = false
    var defaultMode: String? = null
    for ((index, line) in lines.withIndex()) {
        when {
            line.equals("No operations.", ignoreCase = true) -> noOperations = true
            DEFAULT_MODE_LINE.matches(line) -> {
                // Only the line straight after "No operations.", and only once.
                if (index == 0 || !lines[index - 1].equals("No operations.", ignoreCase = true) || defaultMode != null) {
                    return null
                }
                defaultMode = DEFAULT_MODE_LINE.matchEntire(line)!!.groupValues[1].lowercase()
            }
            else -> {
                val match = TAKE_AUDIO_FOCUS_LINE.matchEntire(line) ?: return null
                val mode = match.groupValues[2].lowercase()
                if (match.groupValues[1].isNotEmpty()) uidModes += mode else packageModes += mode
            }
        }
    }
    if (uidModes.any { it != "ignore" && it !in NOT_IGNORED_MODES } ||
        packageModes.any { it != "ignore" && it !in NOT_IGNORED_MODES }
    ) {
        return null
    }
    if ("ignore" in uidModes) return TakeAudioFocusMode.IGNORE
    if (packageModes.isNotEmpty()) {
        if ("ignore" in packageModes) {
            return if (uidModes.isEmpty()) TakeAudioFocusMode.IGNORE else null
        }
        return if (noOperations) null else TakeAudioFocusMode.NOT_IGNORED
    }
    // An explicit uid mode that is not ignore, and nothing at package level: the package may take focus.
    if (uidModes.isNotEmpty()) return TakeAudioFocusMode.NOT_IGNORED
    if (!noOperations) return null
    return when {
        defaultMode == null -> TakeAudioFocusMode.NOT_IGNORED
        defaultMode in NOT_IGNORED_MODES -> TakeAudioFocusMode.NOT_IGNORED
        else -> null
    }
}

private val TAKE_AUDIO_FOCUS_LINE =
    Regex("^(Uid mode:\\s*)?${GameAudioFocusCommands.OP}:\\s*([A-Za-z_]+)(?:;.*)?$")
private val DEFAULT_MODE_LINE = Regex("^Default mode:\\s*([A-Za-z_]+)$")
private val NOT_IGNORED_MODES = setOf("default", "allow", "foreground")

/**
 * The two privileged calls this feature makes. A WRITE ([executeChecked]) runs inline and has no deadline, so that
 * when it returns nothing is still in flight and the readback after it is authoritative. A READBACK ([readChecked])
 * runs under the transport's read deadline and answers the decoded payload of the transport's exit frame, never the
 * raw frame: a hung `appops get` is [CheckedReadResult.Busy], not a worker held forever.
 */
internal interface GameAudioFocusControlPort {
    val available: Boolean
    fun executeChecked(command: String): CheckedCommandResult
    fun readChecked(command: String): CheckedReadResult
}

internal class PServerGameAudioFocusControlPort(
    private val pServer: PServerClient,
) : GameAudioFocusControlPort {
    override val available: Boolean get() = pServer.isAvailable
    override fun executeChecked(command: String): CheckedCommandResult = pServer.executeChecked(command)
    override fun readChecked(command: String): CheckedReadResult = pServer.executeCheckedRead(command)
}

internal sealed interface GameAudioFocusAdmission {
    data object Admitted : GameAudioFocusAdmission
    data class Refused(val code: String, val detail: String) : GameAudioFocusAdmission
}

/** Whether a package may be held, and whether it is still there. Platform facts, behind a seam. */
internal interface GameAudioFocusPackagePolicy {
    /** Installed and not a system app. Mimir's own packages are refused before this is asked. */
    fun admission(packageName: String): GameAudioFocusAdmission

    /** True/false when the platform can say; null when it cannot tell (never treated as gone). */
    fun isInstalled(packageName: String): Boolean?
}

/** The record of what this daemon is holding. [bootIdentity] is diagnostic: a release never depends on it. */
internal data class GameAudioFocusRecord(
    val packageName: String,
    val bootIdentity: String,
    val createdAtElapsedRealtimeMs: Long,
) {
    init {
        require(GameAudioFocusCommands.isValidPackageName(packageName))
        require(BOOT_IDENTITY.matches(bootIdentity))
        require(createdAtElapsedRealtimeMs >= 0L)
    }

    private companion object {
        val BOOT_IDENTITY = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

internal sealed interface GameAudioFocusJournalLoad {
    data object Empty : GameAudioFocusJournalLoad
    data class Loaded(val record: GameAudioFocusRecord) : GameAudioFocusJournalLoad

    /**
     * The file is there and cannot be trusted. [salvagePackage] is the package it still names when that line is a
     * well-formed package: enough to put that one back, never enough to believe the rest of the record.
     */
    data class Corrupt(val salvagePackage: String? = null) : GameAudioFocusJournalLoad

    /** The file could not be read at all (an I/O failure). It is kept, never taken for empty or corrupt. */
    data object Unreadable : GameAudioFocusJournalLoad
}

internal interface GameAudioFocusJournal {
    fun load(): GameAudioFocusJournalLoad
    fun persist(record: GameAudioFocusRecord): Boolean
    fun clear(): Boolean

    /** Move a damaged journal aside (kept, never deleted) so it stops blocking. True when it is no longer at its path. */
    fun quarantine(): Boolean
}

/** The files a journal can leave behind, including its staging spelling: a boot or a daemon start checks these. */
internal fun gameAudioFocusJournalFilesPresent(directory: File?): Boolean = runCatching {
    if (directory == null) return@runCatching false
    val file = File(directory, FileGameAudioFocusJournal.FILE_NAME)
    file.exists() || File(directory, FileGameAudioFocusJournal.FILE_NAME + ".new").exists()
}.getOrDefault(false)

/**
 * One small file, replaced atomically. Plain `java.nio`, no Android types, so it is host-testable.
 *
 * Unsigned on purpose: the file sits in this app's own device-protected storage, only this daemon writes it,
 * and everything read back is re-validated against the package grammar before it is used, so a damaged or
 * hand-edited file can name nothing but a well-formed package and can cause nothing but a `set default`.
 */
internal class FileGameAudioFocusJournal(private val file: File) : GameAudioFocusJournal {
    private val staging = File(file.parentFile, file.name + ".new")

    @Synchronized
    override fun load(): GameAudioFocusJournalLoad {
        if (!file.exists()) {
            // A crash between writing the staging file and renaming it leaves the only copy there.
            if (!staging.exists()) return GameAudioFocusJournalLoad.Empty
            return decode(staging)
        }
        return decode(file)
    }

    @Synchronized
    override fun persist(record: GameAudioFocusRecord): Boolean = try {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val body = listOf(MAGIC, record.packageName, record.bootIdentity, record.createdAtElapsedRealtimeMs.toString())
            .joinToString("\n", postfix = "\n")
            .toByteArray(StandardCharsets.UTF_8)
        java.io.FileOutputStream(staging).use { out ->
            out.write(body)
            out.fd.sync()
        }
        Files.move(
            staging.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    @Synchronized
    override fun quarantine(): Boolean = try {
        val aside = File(file.parentFile, file.name + ".corrupt")
        listOf(file, staging).firstOrNull(File::exists)?.let {
            Files.move(it.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.deleteIfExists(staging.toPath())
        !file.exists() && !staging.exists()
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    @Synchronized
    override fun clear(): Boolean = try {
        Files.deleteIfExists(staging.toPath())
        Files.deleteIfExists(file.toPath())
        !file.exists() && !staging.exists()
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun decode(source: File): GameAudioFocusJournalLoad {
        val lines = try {
            String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8).trimEnd('\n').split('\n')
        } catch (_: IOException) {
            // Not corrupt: the bytes could not be read. The file stays where it is for the next attempt.
            return GameAudioFocusJournalLoad.Unreadable
        }
        val salvage = lines.getOrNull(1)
            ?.takeIf { lines[0] == MAGIC && GameAudioFocusCommands.isValidPackageName(it) }
        return try {
            if (lines.size != 4 || lines[0] != MAGIC) {
                GameAudioFocusJournalLoad.Corrupt(salvage)
            } else {
                GameAudioFocusJournalLoad.Loaded(
                    GameAudioFocusRecord(
                        packageName = lines[1],
                        bootIdentity = lines[2],
                        createdAtElapsedRealtimeMs = lines[3].toLong(),
                    ),
                )
            }
        } catch (_: IllegalArgumentException) {
            GameAudioFocusJournalLoad.Corrupt(salvage)
        }
    }

    companion object {
        const val FILE_NAME = "mimir_game_audio_focus_hold.v1"
        private const val MAGIC = "MIMIR_GAME_AUDIO_FOCUS_V1"
    }
}

internal sealed interface GameAudioFocusHoldResult {
    data class Held(val packageName: String) : GameAudioFocusHoldResult

    /** [releaseDebt] is true only when a put-back was left UNCONFIRMED and the journal still names the package. */
    data class Refused(
        val code: String,
        val detail: String,
        val releaseDebt: Boolean = false,
    ) : GameAudioFocusHoldResult
}

/** What a query established. [Unavailable] is "could not establish", never "not held". */
internal sealed interface GameAudioFocusQueryResult {
    data class Held(val packageName: String) : GameAudioFocusQueryResult
    data object NotHeld : GameAudioFocusQueryResult
    data class Unavailable(val code: String) : GameAudioFocusQueryResult
}

internal sealed interface GameAudioFocusReleaseResult {
    data class Released(val packageName: String, val detail: String = "") : GameAudioFocusReleaseResult
    data object NothingHeld : GameAudioFocusReleaseResult
    data class Retained(val packageName: String?, val code: String) : GameAudioFocusReleaseResult
}

/**
 * The hold itself. Every method runs on the daemon's single transaction worker, and all of them are
 * [Synchronized] so a host test can drive them directly.
 *
 * At most one package is ever held: [hold] puts the previous one back first and, if it cannot, refuses
 * rather than leaving two.
 */
internal class GameAudioFocusController(
    private val journal: GameAudioFocusJournal,
    private val control: GameAudioFocusControlPort,
    private val packages: GameAudioFocusPackagePolicy,
    private val bootIdentity: () -> String?,
    private val elapsedRealtimeMs: () -> Long,
    private val sleepMs: (Long) -> Unit = Thread::sleep,
    private val log: (String) -> Unit = {},
) {
    @Synchronized
    fun hold(packageName: String): GameAudioFocusHoldResult {
        val result = holdInternal(packageName)
        log(
            "GAME_AUDIO_FOCUS hold package=${loggable(packageName)} " +
                when (result) {
                    is GameAudioFocusHoldResult.Held -> "outcome=HELD"
                    is GameAudioFocusHoldResult.Refused ->
                        "outcome=REFUSED code=${result.code} releaseDebt=${result.releaseDebt}"
                },
        )
        return result
    }

    private fun holdInternal(packageName: String): GameAudioFocusHoldResult {
        if (!GameAudioFocusCommands.isValidPackageName(packageName)) {
            return refused(TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE, "not a package name")
        }
        if (GameAudioFocusCommands.isMimirPackage(packageName)) {
            return refused(TweakerGameAudioFocusContract.REFUSAL_SELF_PACKAGE, "a Mimir package is never held")
        }
        (packages.admission(packageName) as? GameAudioFocusAdmission.Refused)?.let {
            return refused(it.code, it.detail)
        }
        val holdCommand = GameAudioFocusCommands.hold(packageName)
        val readCommand = GameAudioFocusCommands.read(packageName)
        if (holdCommand == null || readCommand == null || GameAudioFocusCommands.release(packageName) == null) {
            return refused(TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE, "command does not fit")
        }
        if (!control.available) {
            return refused(TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE, "no privileged transport")
        }
        // A new hold puts the previous one back first, and refuses rather than hold two.
        when (val previous = releaseInternal()) {
            is GameAudioFocusReleaseResult.Retained -> return GameAudioFocusHoldResult.Refused(
                code = TweakerGameAudioFocusContract.REFUSAL_RELEASE_PREVIOUS_FAILED,
                detail = "previous hold ${loggable(previous.packageName)} not confirmed put back: ${previous.code}",
                releaseDebt = true,
            )
            is GameAudioFocusReleaseResult.Released, GameAudioFocusReleaseResult.NothingHeld -> Unit
        }
        val record = runCatching {
            GameAudioFocusRecord(
                packageName = packageName,
                bootIdentity = bootIdentity()?.trim()?.takeIf(String::isNotEmpty) ?: UNKNOWN_BOOT,
                createdAtElapsedRealtimeMs = elapsedRealtimeMs().coerceAtLeast(0L),
            )
        }.getOrNull() ?: return refused(TweakerGameAudioFocusContract.REFUSAL_JOURNAL_UNAVAILABLE, "record invalid")
        // Journalled BEFORE the command: a death between the two leaves a record that names a package
        // whose op may or may not have changed, and every release path is idempotent.
        if (!journal.persist(record)) {
            return refused(TweakerGameAudioFocusContract.REFUSAL_JOURNAL_UNAVAILABLE, "journal not durable")
        }
        val execution = control.executeChecked(holdCommand)
        val readback = boundedReadback(readCommand)
        val mode = (readback as? Readback.Mode)?.mode
        if (mode == TakeAudioFocusMode.IGNORE) return GameAudioFocusHoldResult.Held(packageName)
        if (mode == null && readback !is Readback.Busy && execution.status == CheckedCommandStatus.FAILED) {
            // The command ran and exited non-zero (or never reached a shell), and the op cannot be read either:
            // the route is down, and nothing was written. Keeping the journal would make every later launch
            // look broken -- the put-back would fail the same way and refuse the next hold as
            // RELEASE_PREVIOUS_FAILED -- for a package that was never held. A command that AMBIGUOUSLY ran is
            // different, and falls through to the rollback with its debt kept.
            val cleared = journal.clear()
            return GameAudioFocusHoldResult.Refused(
                code = TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE,
                detail = "command FAILED and the app-op is unreadable; nothing was changed",
                releaseDebt = !cleared,
            )
        }
        // Not held, or not provably held: put it back at once. The command's own status is only a hint.
        val rollback = releaseInternal()
        val debt = rollback is GameAudioFocusReleaseResult.Retained
        return GameAudioFocusHoldResult.Refused(
            code = if (readback is Readback.Busy) {
                TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY
            } else {
                TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD
            },
            detail = "command ${execution.status.name}, readback ${readback.label()}",
            releaseDebt = debt,
        )
    }

    /** Put back whatever is held, and read it back. Safe to call with nothing held. */
    @Synchronized
    fun release(): GameAudioFocusReleaseResult {
        val result = releaseInternal()
        log(
            "GAME_AUDIO_FOCUS release " +
                when (result) {
                    is GameAudioFocusReleaseResult.Released ->
                        "package=${loggable(result.packageName)} outcome=RELEASED ${result.detail}".trimEnd()
                    GameAudioFocusReleaseResult.NothingHeld -> "outcome=NOTHING_HELD"
                    is GameAudioFocusReleaseResult.Retained ->
                        "package=${loggable(result.packageName)} outcome=RETAINED code=${result.code}"
                },
        )
        return result
    }

    @Synchronized
    fun heldPackage(): String? = (journal.load() as? GameAudioFocusJournalLoad.Loaded)?.record?.packageName

    /**
     * What is held NOW, established by reading the app-op back (bounded), never from the journal alone: the journal
     * names the package, the readback says whether it is still at ignore. Writes nothing. A query that could not
     * establish the answer is [GameAudioFocusQueryResult.Unavailable], never [GameAudioFocusQueryResult.NotHeld].
     */
    @Synchronized
    fun query(): GameAudioFocusQueryResult {
        val packageName = when (val load = journal.load()) {
            GameAudioFocusJournalLoad.Empty -> return GameAudioFocusQueryResult.NotHeld
            GameAudioFocusJournalLoad.Unreadable -> return GameAudioFocusQueryResult.Unavailable(CODE_JOURNAL_UNREADABLE)
            is GameAudioFocusJournalLoad.Corrupt ->
                load.salvagePackage ?: return GameAudioFocusQueryResult.Unavailable(CODE_RECOVERY_NEEDED)
            is GameAudioFocusJournalLoad.Loaded -> load.record.packageName
        }
        val readCommand = GameAudioFocusCommands.read(packageName)
            ?: return GameAudioFocusQueryResult.Unavailable(CODE_RECOVERY_NEEDED)
        if (!control.available) {
            return GameAudioFocusQueryResult.Unavailable(TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE)
        }
        return when (val readback = boundedReadback(readCommand)) {
            is Readback.Mode -> when (readback.mode) {
                TakeAudioFocusMode.IGNORE -> GameAudioFocusQueryResult.Held(packageName)
                TakeAudioFocusMode.NOT_IGNORED -> GameAudioFocusQueryResult.NotHeld
            }
            Readback.Busy -> GameAudioFocusQueryResult.Unavailable(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY)
            Readback.Unreadable -> GameAudioFocusQueryResult.Unavailable(CODE_READBACK_UNREADABLE)
        }
    }

    @Synchronized
    fun journalPresent(): Boolean = journal.load() !is GameAudioFocusJournalLoad.Empty

    private fun releaseInternal(): GameAudioFocusReleaseResult {
        // A journal that cannot be believed is never taken for "nothing held", and never deleted in silence.
        val packageName: String
        val damaged: Boolean
        when (val load = journal.load()) {
            GameAudioFocusJournalLoad.Empty -> return GameAudioFocusReleaseResult.NothingHeld
            GameAudioFocusJournalLoad.Unreadable -> {
                // An I/O failure, not damage: the file stays exactly where it is and the next attempt reads it again.
                log("GAME_AUDIO_FOCUS journal=UNREADABLE kept")
                return GameAudioFocusReleaseResult.Retained(null, CODE_JOURNAL_UNREADABLE)
            }
            is GameAudioFocusJournalLoad.Corrupt -> {
                val salvage = load.salvagePackage
                if (salvage == null) {
                    // Names no package, so nothing can be put back. It is set aside, not deleted, so the owner can
                    // read it, and the caller is told recovery is needed -- which is not "nothing held".
                    log("GAME_AUDIO_FOCUS journal=CORRUPT unparseable, quarantined")
                    return GameAudioFocusReleaseResult.Retained(
                        null,
                        if (journal.quarantine()) CODE_RECOVERY_NEEDED else CODE_CORRUPT_NOT_QUARANTINED,
                    )
                }
                log("GAME_AUDIO_FOCUS journal=CORRUPT salvaged package=${loggable(salvage)}")
                packageName = salvage
                damaged = true
            }
            is GameAudioFocusJournalLoad.Loaded -> {
                packageName = load.record.packageName
                damaged = false
            }
        }
        val releaseCommand = GameAudioFocusCommands.release(packageName)
        val readCommand = GameAudioFocusCommands.read(packageName)
        if (releaseCommand == null || readCommand == null) {
            return GameAudioFocusReleaseResult.Retained(packageName, "RELEASE_COMMAND_UNAVAILABLE")
        }
        if (!control.available) {
            return GameAudioFocusReleaseResult.Retained(packageName, "HOST_UNAVAILABLE")
        }
        control.executeChecked(releaseCommand)
        val detail = if (damaged) "journalCorrupt=true" else ""
        return when (val readback = boundedReadback(readCommand)) {
            is Readback.Mode -> when (readback.mode) {
                TakeAudioFocusMode.NOT_IGNORED -> clearAfterRelease(packageName, detail)
                TakeAudioFocusMode.IGNORE -> GameAudioFocusReleaseResult.Retained(packageName, "RELEASE_NOT_OBSERVED")
            }
            Readback.Busy -> GameAudioFocusReleaseResult.Retained(packageName, TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY)
            // Unreadable. An uninstalled package takes its app-op with it, and a record that can never
            // be confirmed would otherwise refuse every later hold.
            Readback.Unreadable -> if (packages.isInstalled(packageName) == false) {
                clearAfterRelease(packageName, (detail + " packageGone=true").trim())
            } else {
                GameAudioFocusReleaseResult.Retained(packageName, "RELEASE_READBACK_UNCERTAIN")
            }
        }
    }

    private fun clearAfterRelease(packageName: String, detail: String): GameAudioFocusReleaseResult =
        if (journal.clear()) {
            GameAudioFocusReleaseResult.Released(packageName, detail)
        } else {
            GameAudioFocusReleaseResult.Retained(packageName, "RELEASE_CLEAR_FAILED")
        }

    /** What one bounded readback established. [Busy] is not retried: a wedged read only deepens the wedge. */
    private sealed interface Readback {
        data class Mode(val mode: TakeAudioFocusMode) : Readback
        data object Unreadable : Readback
        data object Busy : Readback
    }

    private fun Readback.label(): String = when (this) {
        is Readback.Mode -> mode.name
        Readback.Busy -> "BUSY"
        Readback.Unreadable -> "UNREADABLE"
    }

    /**
     * Reads the op back through the transport's decoded frame payload, under the transport's read deadline. The reply is
     * never parsed as bare text: it is the payload of an exit-0 frame or it is nothing.
     */
    private fun boundedReadback(readCommand: String): Readback {
        repeat(READBACK_ATTEMPTS) { attempt ->
            when (val result = control.readChecked(readCommand)) {
                is CheckedReadResult.Body -> parseTakeAudioFocusMode(result.text)?.let { return Readback.Mode(it) }
                is CheckedReadResult.Busy -> return Readback.Busy
                is CheckedReadResult.Failed, is CheckedReadResult.Unreadable -> Unit
            }
            if (attempt + 1 < READBACK_ATTEMPTS) sleepMs(READBACK_SETTLE_MS)
        }
        return Readback.Unreadable
    }

    private fun refused(code: String, detail: String) = GameAudioFocusHoldResult.Refused(code, detail)

    /** A package that matched the grammar is safe to log; anything else is not echoed. */
    private fun loggable(packageName: String?): String =
        packageName?.takeIf(GameAudioFocusCommands::isValidPackageName) ?: "-"

    private companion object {
        const val READBACK_ATTEMPTS = 2
        const val READBACK_SETTLE_MS = 50L
        const val UNKNOWN_BOOT = "unknown"
        const val CODE_RECOVERY_NEEDED = "RECOVERY_NEEDED"
        const val CODE_JOURNAL_UNREADABLE = "JOURNAL_UNREADABLE"
        const val CODE_CORRUPT_NOT_QUARANTINED = "JOURNAL_CORRUPT_NOT_QUARANTINED"
        const val CODE_READBACK_UNREADABLE = "READBACK_UNREADABLE"
    }
}

/**
 * The owner of the hold: the client's Binder token, whose death releases it.
 *
 * Lighter than [PublicOwnerRegistry] on purpose. A hold has no lease, no generation and no restore
 * identity; the token only answers "is the caller still alive". The daemon never trusts it for anything
 * else: [release] takes no identity either, because the caller already holds CONTROL_TWEAKER.
 */
internal class GameAudioFocusSession(
    private val controller: GameAudioFocusController,
    private val executeAsync: (() -> Unit) -> Unit,
) {
    private val lock = Any()
    private var owner: ClientTokenHandle? = null

    /**
     * Ownership moves only after the new hold is ADMITTED AND CONFIRMED. Until then the previous owner and its death link
     * stay exactly as they were, so a refused replacement (a retained put-back, a failed readback, a busy route) can never
     * leave a surviving hold without its Binder-death cleanup. The previous owner's link is dropped last, once the new
     * owner's own link is established.
     */
    fun hold(token: ClientTokenHandle, packageName: String): GameAudioFocusHoldResult {
        if (!token.isAlive()) {
            return GameAudioFocusHoldResult.Refused(
                TweakerGameAudioFocusContract.REFUSAL_CLIENT_TOKEN_DEAD,
                "client token is not alive",
            )
        }
        val result = controller.hold(packageName)
        if (result !is GameAudioFocusHoldResult.Held) return result
        if (!token.linkToDeath { onOwnerDeath(token) }) {
            // The client died between the liveness check and the link: nobody is left to release this hold, so it is
            // put back now rather than reported.
            token.unlinkFromDeath()
            val put = controller.release()
            return GameAudioFocusHoldResult.Refused(
                TweakerGameAudioFocusContract.REFUSAL_CLIENT_TOKEN_DEAD,
                "client token died before the hold could be owned",
                releaseDebt = put is GameAudioFocusReleaseResult.Retained,
            )
        }
        val previous = synchronized(lock) { owner.also { owner = token } }
        if (previous !== token) previous?.unlinkFromDeath()
        return result
    }

    fun release(): GameAudioFocusReleaseResult {
        val result = controller.release()
        if (result !is GameAudioFocusReleaseResult.Retained) dropOwner()
        return result
    }

    /** Daemon start: nothing owns the journal, so whatever it names is put back. */
    fun recoverOrphan(): GameAudioFocusReleaseResult = release()

    fun heldPackage(): String? = controller.heldPackage()

    fun query(): GameAudioFocusQueryResult = controller.query()

    fun ownerPresent(): Boolean = synchronized(lock) { owner != null }

    private fun dropOwner() {
        val previous = synchronized(lock) { owner.also { owner = null } }
        previous?.unlinkFromDeath()
    }

    private fun onOwnerDeath(token: ClientTokenHandle) {
        executeAsync {
            val current = synchronized(lock) { owner === token }
            if (current) {
                val result = controller.release()
                if (result !is GameAudioFocusReleaseResult.Retained) dropOwner()
            }
        }
    }
}

/**
 * Who may call the v11 methods: Mimir's own app, and this daemon itself.
 *
 * The signature permission only proves the caller shares this daemon's signing key. The hold is Mimir's alone, so the
 * calling UID must also belong to one of the approved Mimir application ids, EXACTLY (no prefix and no wildcard: a
 * same-signed `com.gruning.mimir.other` is not Mimir). The approved ids are the product's, plus the side-by-side `devlab`
 * variant of the same app, which only exists beside a debug-signed build: it is admitted only where [devVariantsAllowed]
 * says so, and the service passes that for debug builds. A uid shared by several packages is admitted when any of them
 * is approved, which is how Android itself models shared uids. Nothing the caller sends is consulted: the UID is the
 * kernel's.
 */
internal object GameAudioFocusCallerPolicy {
    /** The Mimir app, the only product client of the hold. */
    const val MIMIR_APP_PACKAGE = "com.gruning.mimir.rewrite"

    /** The runnable `devlab` variant of the same app (app/build.gradle.kts), debug-signed and side by side. */
    const val MIMIR_DEVLAB_PACKAGE = "com.gruning.mimir.rewrite.devlab"

    fun approvedPackages(devVariantsAllowed: Boolean): Set<String> =
        if (devVariantsAllowed) setOf(MIMIR_APP_PACKAGE, MIMIR_DEVLAB_PACKAGE) else setOf(MIMIR_APP_PACKAGE)

    fun admits(
        callingUid: Int,
        ownUid: Int,
        devVariantsAllowed: Boolean = false,
        packagesForUid: (Int) -> List<String>,
    ): Boolean {
        if (callingUid == ownUid) return true
        val packages = try {
            packagesForUid(callingUid)
        } catch (_: RuntimeException) {
            return false
        }
        val approved = approvedPackages(devVariantsAllowed)
        return packages.any { it in approved }
    }
}
