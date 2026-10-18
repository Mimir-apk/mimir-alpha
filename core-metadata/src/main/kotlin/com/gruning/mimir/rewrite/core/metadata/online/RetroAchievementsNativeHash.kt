package com.gruning.mimir.rewrite.core.metadata.online

/**
 * Thin Android boundary over the pinned upstream `rcheevos` disc/seek algorithms.
 *
 * The native reader is deliberately path-only. The algorithms seek repeatedly and a SAF [java.io.InputStream]
 * cannot satisfy that contract without copying a potentially multi-gigabyte image. Callers resolve a real local
 * path when all-files access makes one available; an unresolved document remains a typed file-unavailable state.
 */
internal object RetroAchievementsNativeHash {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("mimir_ra_hash")
        true
    }.getOrDefault(false)

    fun hash(consoleId: Int, localPath: String): String? =
        if (!loaded || localPath.isBlank()) null else runCatching { nativeHash(consoleId, localPath) }.getOrNull()

    /**
     * Hashes [targetPath] while the already verified libchdr chain at [nativeChainHandle] remains borrowed.
     * The native reader never closes the handle; its Kotlin verification owner closes it after this returns.
     */
    fun hashChain(consoleId: Int, targetPath: String, nativeChainHandle: Long): String? =
        if (!loaded || targetPath.isBlank() || nativeChainHandle == 0L) {
            null
        } else {
            runCatching { nativeHashChain(consoleId, targetPath, nativeChainHandle) }.getOrNull()
        }

    fun isAvailable(): Boolean = loaded

    private external fun nativeHash(consoleId: Int, path: String): String?
    private external fun nativeHashChain(consoleId: Int, path: String, nativeChainHandle: Long): String?
}
