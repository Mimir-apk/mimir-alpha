package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

private const val EMULATOR_RENDER_FPS_ASSET_PATH = "mimir-render-fps/render_fps_helper"

private fun emulatorRenderFpsAssetReader(context: Context): () -> InputStream? = {
    runCatching { context.assets.open(EMULATOR_RENDER_FPS_ASSET_PATH) }.getOrNull()
}

/** Fixed private files consumed by the no-argument native renderer probe. */
internal data class EmulatorRenderFpsHelperFiles(
    val directory: File,
    val helper: File,
    val resolverScript: File,
    val launcherScript: File,
    val readerScript: File,
    val request: File,
    val record: File,
)

/**
 * Stages the source-built helper and owns the request file. The root vendor transport invokes the
 * fixed private reader, which validates and bounds the record before emitting base64; the app never
 * trusts a direct record path.
 */
internal class EmulatorRenderFpsHelperStager(
    private val filesDir: File,
    private val assetReader: () -> InputStream? = { null },
) {
    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        assetReader = emulatorRenderFpsAssetReader(context.applicationContext),
    )

    @Synchronized
    fun stage(): EmulatorRenderFpsHelperFiles? {
        val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
        if (!validRoot(root)) return null
        val directory = File(root, DIRECTORY_NAME)
        if (!validDirectory(root, directory) &&
            (!directory.mkdirs() || !validDirectory(root, directory))
        ) return null
        val helper = File(directory, HELPER_NAME)
        val resolver = File(directory, RESOLVER_NAME)
        val launcher = File(directory, LAUNCHER_NAME)
        val reader = File(directory, READER_NAME)
        // Replace the source-built ELF on every stage so an app update cannot reuse an older helper.
        val input = assetReader() ?: return null
        if (!writeAtomic(input, helper) || !helper.setExecutable(true, false)) return null
        if (!validScript(root, resolver, EMULATOR_RENDER_FPS_RESOLVER_SCRIPT)) {
            if (!writeScriptAtomic(resolver, EMULATOR_RENDER_FPS_RESOLVER_SCRIPT)) return null
        }
        if (!validScript(root, launcher, EMULATOR_RENDER_FPS_LAUNCHER_SCRIPT)) {
            if (!writeScriptAtomic(launcher, EMULATOR_RENDER_FPS_LAUNCHER_SCRIPT)) return null
        }
        if (!validScript(root, reader, EMULATOR_RENDER_FPS_READER_SCRIPT)) {
            if (!writeScriptAtomic(reader, EMULATOR_RENDER_FPS_READER_SCRIPT)) return null
        }
        if (!validHelper(root, helper) ||
            !validScript(root, resolver, EMULATOR_RENDER_FPS_RESOLVER_SCRIPT) ||
            !validScript(root, launcher, EMULATOR_RENDER_FPS_LAUNCHER_SCRIPT) ||
            !validScript(root, reader, EMULATOR_RENDER_FPS_READER_SCRIPT)
        ) return null
        return EmulatorRenderFpsHelperFiles(
            directory = directory,
            helper = helper,
            resolverScript = resolver,
            launcherScript = launcher,
            readerScript = reader,
            request = File(directory, REQUEST_NAME),
            record = File(directory, RECORD_NAME),
        )
    }

    fun writeRequest(files: EmulatorRenderFpsHelperFiles, request: EmulatorRenderFpsRequest): Boolean {
        val text = formatEmulatorRenderFpsRequest(request) ?: return false
        if (!belongsToStagedDirectory(files, files.request)) return false
        return writeTextAtomic(files.request, text)
    }

    fun clear(files: EmulatorRenderFpsHelperFiles) {
        if (!belongsToStagedDirectory(files, files.request)) return
        runCatching { files.request.delete() }
        runCatching { files.record.delete() }
    }

    fun clearRecord(files: EmulatorRenderFpsHelperFiles) {
        if (!belongsToStagedDirectory(files, files.record)) return
        runCatching { files.record.delete() }
    }

    private fun writeAtomic(input: InputStream, destination: File): Boolean {
        val temporary = runCatching { File.createTempFile("helper-", ".tmp", destination.parentFile) }
            .getOrNull() ?: return false
        return try {
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            atomicMove(temporary, destination)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun writeScriptAtomic(destination: File, content: String): Boolean {
        val temporary = runCatching { File.createTempFile("resolver-", ".tmp", destination.parentFile) }
            .getOrNull() ?: return false
        return try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            atomicMove(temporary, destination) && destination.setExecutable(true, false)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun writeTextAtomic(destination: File, text: String): Boolean {
        val temporary = runCatching { File.createTempFile("request-", ".tmp", destination.parentFile) }
            .getOrNull() ?: return false
        return try {
                FileOutputStream(temporary).use { output ->
                    output.write(text.toByteArray(StandardCharsets.UTF_8))
                }
            atomicMove(temporary, destination)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    private fun atomicMove(source: File, destination: File): Boolean = runCatching {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrElse {
        source.renameTo(destination)
    }

    private fun belongsToStagedDirectory(files: EmulatorRenderFpsHelperFiles, file: File): Boolean = runCatching {
        val root = filesDir.canonicalFile.toPath()
        val directory = files.directory.canonicalFile.toPath()
        val path = file.toPath()
        directory == File(filesDir, DIRECTORY_NAME).canonicalFile.toPath() &&
            path.parent == directory &&
            path.normalize().startsWith(root) &&
            !Files.isSymbolicLink(path)
    }.getOrDefault(false)

    private fun validRoot(root: File): Boolean = runCatching {
        !Files.isSymbolicLink(filesDir.toPath()) && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun validDirectory(root: File, directory: File): Boolean = runCatching {
        !Files.isSymbolicLink(directory.toPath()) &&
            Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) &&
            directory.canonicalFile.toPath().startsWith(root.toPath())
    }.getOrDefault(false)

    private fun validHelper(root: File, helper: File): Boolean = runCatching {
        !Files.isSymbolicLink(helper.toPath()) &&
            Files.isRegularFile(helper.toPath(), NOFOLLOW_LINKS) &&
            helper.canExecute() &&
            helper.canonicalFile.toPath().startsWith(root.toPath())
    }.getOrDefault(false)

    private fun validScript(root: File, script: File, expected: String): Boolean = runCatching {
        !Files.isSymbolicLink(script.toPath()) &&
            Files.isRegularFile(script.toPath(), NOFOLLOW_LINKS) &&
            script.canExecute() &&
            script.readBytes().contentEquals(expected.toByteArray(StandardCharsets.UTF_8)) &&
            script.canonicalFile.toPath().startsWith(root.toPath())
    }.getOrDefault(false)

    private companion object {
        const val DIRECTORY_NAME = "mimir-render-fps"
        const val HELPER_NAME = "render_fps_helper"
        const val RESOLVER_NAME = "resolve_target.sh"
        const val LAUNCHER_NAME = "start_render_fps.sh"
        const val READER_NAME = "read_render_fps.sh"
        const val REQUEST_NAME = "request.v1"
        const val RECORD_NAME = "record.v1"
    }
}

private val EMULATOR_RENDER_FPS_PACKAGE_PATTERN: String =
    EmulatorSeedRegistry.allDispatchPackages().sorted().joinToString("|")

/** Root-side identity lookup; the helper then rechecks every field before attaching probes. */
internal val EMULATOR_RENDER_FPS_RESOLVER_SCRIPT = """#!/system/bin/sh
set -f
package=${'$'}1
case "${'$'}package" in
  ${EMULATOR_RENDER_FPS_PACKAGE_PATTERN}) ;;
  *) exit 0 ;;
esac
case "${'$'}package" in
  org.mupen64plusae.v3.alpha|org.mupen64plusae.v3.fzurita|org.mupen64plusae.v3.fzurita.pro)
    process=paulscode.android.mupen64plusae.GameActivity ;;
  com.izzy2lost.x1box) process="${'$'}package:xemu" ;;
  *) process="${'$'}package" ;;
esac
pid_list=$(/system/bin/pidof "${'$'}process") || pid_list=
set -- ${'$'}pid_list
[ "${'$'}#" -eq 1 ] || exit 0
pid=${'$'}1
case "${'$'}pid" in ''|*[!0-9]*) exit 0 ;; esac
[ "${'$'}{#pid}" -le 10 ] || exit 0
# Android mksh arithmetic is signed 32-bit on certified devices. Compare these bounded
# decimal integers in awk (exact through 2^53), never against UINT32_MAX in shell test.
# The registry app ID describes user 0; another user's UID fails the exact equality.
package_uid=$(/system/bin/awk -v name="${'$'}package" -v pid="${'$'}pid" '
  ${'$'}1 == name { count++; uid=${'$'}2 }
  END {
    if (count == 1 && uid ~ /^[0-9]+${'$'}/ && length(uid) <= 10 &&
        uid + 0 <= 4294967295 && pid + 0 > 0 && pid + 0 <= 2147483647) print uid
  }
' /data/system/packages.list) || exit 0
case "${'$'}package_uid" in ''|*[!0-9]*) exit 0 ;; esac
stat=$(/system/bin/cat "/proc/${'$'}pid/stat") || exit 0
rest=${'$'}{stat##*\) }
set -- ${'$'}rest
[ "${'$'}#" -ge 20 ] || exit 0
start=${'$'}{20}
case "${'$'}start" in ''|0|*[!0-9]*) exit 0 ;; esac
[ "${'$'}{#start}" -le 20 ] || exit 0
uid=$(/system/bin/awk '/^Uid:/{print ${'$'}2; exit}' "/proc/${'$'}pid/status") || exit 0
case "${'$'}uid" in ''|*[!0-9]*) exit 0 ;; esac
[ "${'$'}{#uid}" -le 10 ] && [ "${'$'}uid" = "${'$'}package_uid" ] || exit 0
/system/bin/printf '%s %s %s\n' "${'$'}pid" "${'$'}uid" "${'$'}start"
"""

/** Short fixed launcher keeps the PServer command within its measured 198-byte envelope. */
internal val EMULATOR_RENDER_FPS_LAUNCHER_SCRIPT = """#!/system/bin/sh
set -f
script_dir=${'$'}{0%/*}
/system/bin/nohup "${'$'}script_dir/render_fps_helper" >/dev/null 2>&1 &
/system/bin/echo __MIMIR_VENDOR_OK__
"""

/** Reads only a validated native record, keeping all remote output below the PServer allocation. */
internal val EMULATOR_RENDER_FPS_READER_SCRIPT = """#!/system/bin/sh
set -f
script_dir=${'$'}{0%/*}
record="${'$'}script_dir/record.v1"
if [ ! -f "${'$'}record" ]; then
  /system/bin/printf '%s\n' "${EMULATOR_RENDER_FPS_MISSING_RECORD_SENTINEL}"
  exit 0
fi
size=$(/system/bin/wc -c < "${'$'}record") || exit 0
case "${'$'}size" in ''|*[!0-9]*) exit 0 ;; esac
[ "${'$'}size" -le 512 ] || exit 0
encoded=$(/system/bin/base64 -w 0 "${'$'}record") || exit 0
case "${'$'}encoded" in ''|*[!A-Za-z0-9+/=]*) exit 0 ;; esac
[ "${'$'}{#encoded}" -le ${EMULATOR_RENDER_FPS_MAX_WIRE_BASE64_CHARS} ] || exit 0
/system/bin/printf '%s\n' "${'$'}encoded"
"""
