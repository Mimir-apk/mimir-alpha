package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.RetroArchCoreListing
import java.nio.charset.StandardCharsets

/**
 * "Which cores does this RetroArch have" -- one fixed, read-only privileged listing, and nothing else.
 *
 * a RetroArch launch whose libretro core is not installed
 * shows a black screen that Mimir cannot tell from a slow start, and Mimir cannot read RetroArch's private folder
 * itself. This asks the one thing the player needs answered, for the known RetroArch packages only:
 *  - the package is one of [AllowedPackages], an exact allowlist -- not a pattern, not a prefix, not caller text;
 *  - the user id is a non-negative Int, formatted by Kotlin;
 *  - the path is `/data/user/<userId>/<package>/cores`, so no caller-supplied path or command fragment exists;
 *  - it lists and prints. It writes nothing, stops nothing, and runs nothing from the folder.
 *
 * **Device fact** (Thor, build `23ca3300`; see [VendorPServerOperation.ResolveTask]): this channel returns only a
 * command's FIRST output line, and `ls -1` prints one name per line, so a bare listing would come back as the
 * alphabetically first core and read as a folder of one. The names are therefore joined in the shell: the
 * deliberately UNQUOTED `$L` word-splits on the newlines and `echo` prints it on one line, ended by
 * [RetroArchCoreListing.REPLY_END_MARKER]. [RetroArchCoreListing.parse] requires the marker and rejects any token
 * before it that is not a plain core file name, so a cut-short reply is Unknown, never "missing".
 *
 * Unquoted `$L` also undergoes pathname expansion against the vendor shell's working directory. No admitted core
 * name carries `*`, `?` or `[`, so none occurs for a real folder; a folder entry that did carry one would either
 * expand into stray tokens or stay literal, and in both cases [RetroArchCoreListing.parse] rejects the reply as
 * Unknown (fail-open), never as a folder that lacks a core.
 *
 * The shape is [VendorPServerOperation.ResolveTaskOutcome]'s, the one already seen running on the Thor: `if` on the
 * capture's own exit status, no `exit`, builtin `echo`. When `ls` fails (no such folder, a denied read) the `then`
 * never runs and the reply is empty -- also Unknown, so the launch goes ahead exactly as it did before this check.
 *
 * The reply is the raw first line. Reading it is [RetroArchCoreListing.parse]'s, and deciding what it means is
 * `core-launch`'s: nothing here can refuse a launch.
 */
internal class ListRetroArchCores private constructor(
    private val userId: Int,
    private val packageName: String,
) : VendorPServerOperation {
    override fun render(): String =
        "if L=\$(/system/bin/ls -1 /data/user/$userId/$packageName/cores 2>/dev/null); " +
            "then echo \$L ${RetroArchCoreListing.REPLY_END_MARKER}; fi"

    companion object {
        /**
         * The RetroArch builds Mimir launches: the 64-bit, universal and 32-bit packages `EmulatorSeedRegistry` seeds
         * and `ShippingLaunchProfiles` rebases the core path onto. A fixed list, kept apart from `core-launch`'s on
         * purpose so widening what the privileged shell is asked about is a change to THIS file; tests pin it against
         * `EmulatorSeedRegistry` here and, in `core-launch`, against `ShippingLaunchProfiles`.
         */
        val AllowedPackages: Set<String> = setOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32")

        fun create(userId: Int, packageName: String): ListRetroArchCores? {
            if (userId < 0) return null
            return packageName
                .takeIf { it in AllowedPackages }
                // Defence in depth at the composition site, as ForceStop does: refuse to render a metacharacter even
                // if the allowlist is ever edited carelessly.
                ?.takeIf { VendorPServerOperation.ShellSafePackage.matches(it) }
                ?.let { ListRetroArchCores(userId, it) }
                ?.takeIf {
                    it.render().toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes
                }
        }
    }
}
