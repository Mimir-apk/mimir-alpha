package com.gruning.mimir.rewrite

/**
 * Fail-closed policy for MainActivity's adb/device-proof intent extras.
 *
 * MainActivity must be exported for LAUNCHER and HOME, so checking for a magic extra is not caller
 * authentication. BuildConfig.DEBUG is the authority: release artifacts never execute the block.
 */
internal class DebugIntentHookGate(
    private val debugBuild: Boolean,
) {
    fun run(block: () -> Unit) {
        if (debugBuild) block()
    }
}
