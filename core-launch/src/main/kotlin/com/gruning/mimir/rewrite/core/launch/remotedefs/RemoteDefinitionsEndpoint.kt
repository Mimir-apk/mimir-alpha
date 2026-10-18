package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.net.URI

/**
 * Where the data lives: the Mimir-owned data repository, changed through reviewed merges.
 *
 * Two files are ever requested, and nothing else:
 *
 *   `main/pointer.json`         a release pointer containing: `{ordinal, tag, sha256}` (the only mutable thing)
 *   `<tag>/definitions.json`    the immutable file at a tag `defs-<ordinal>`, checked against the pointer's SHA-256
 *
 * The data never chooses its own origin: no field in either file is a URL, and every redirect is checked against
 * [RemoteDefinitionsSource.urlAllowed] again. A branch name other than the pointer's own `main` is never accepted.
 */
object RemoteDefinitionsEndpoint {
    const val HOST: String = "raw.githubusercontent.com"
    const val REPO_PATH: String = "/Mimir-apk/mimir-emulator-definitions"

    /** The one base URL: everything the app ever requests is `BASE_URL` plus a fixed, validated tail. */
    const val BASE_URL: String = "https://$HOST$REPO_PATH"

    const val FILE_NAME: String = "definitions.json"
    const val POINTER_FILE_NAME: String = "pointer.json"
    const val POINTER_BRANCH: String = "main"

    /** `https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json` */
    const val POINTER_URL: String = "$BASE_URL/$POINTER_BRANCH/$POINTER_FILE_NAME"

    /** The only source production code ever uses. */
    val PRODUCTION: RemoteDefinitionsSource = RemoteDefinitionsSource("https", HOST, -1, REPO_PATH)

    fun urlFor(ref: String): String? = PRODUCTION.urlFor(ref)

    fun urlAllowed(url: String): Boolean = PRODUCTION.urlAllowed(url)
}

/**
 * An allow-listed origin. The constructor is `internal` on purpose: the app can only use
 * [RemoteDefinitionsEndpoint.PRODUCTION], while this module's tests build a loopback source to talk to a fake server.
 */
class RemoteDefinitionsSource internal constructor(
    private val scheme: String,
    private val host: String,
    /** -1 means the scheme's default port. */
    private val port: Int,
    private val repoPath: String,
) {
    /** An immutable tag (`defs-42`) or a full commit id. A branch name such as `main` is never accepted. */
    private val ref = Regex("^(?:defs-[0-9]{1,${RemoteDefinitionsLimits.MAX_ORDINAL.toString().length}}|[0-9a-f]{40})$")

    val pointerUrl: String get() = origin() + "$repoPath/${RemoteDefinitionsEndpoint.POINTER_BRANCH}/${RemoteDefinitionsEndpoint.POINTER_FILE_NAME}"

    fun urlFor(ref: String): String? =
        if (this.ref.matches(ref)) origin() + "$repoPath/$ref/${RemoteDefinitionsEndpoint.FILE_NAME}" else null

    /**
     * Exact scheme, host, port and path shape; no user info, query or fragment. Used for the first URL and for every
     * redirect. The path is either `<repo>/<immutable ref>/definitions.json` or exactly the pointer's own path.
     */
    fun urlAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != scheme || uri.host != host) return false
        val expectedPort = if (port == -1) defaultPort() else port
        if ((if (uri.port == -1) defaultPort() else uri.port) != expectedPort) return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath ?: return false
        if (path == "$repoPath/${RemoteDefinitionsEndpoint.POINTER_BRANCH}/${RemoteDefinitionsEndpoint.POINTER_FILE_NAME}") return true
        if (!path.startsWith("$repoPath/") || !path.endsWith("/${RemoteDefinitionsEndpoint.FILE_NAME}")) return false
        val tail = path.removePrefix("$repoPath/").removeSuffix("/${RemoteDefinitionsEndpoint.FILE_NAME}")
        return ref.matches(tail)
    }

    private fun defaultPort(): Int = if (scheme == "https") 443 else 80

    private fun origin(): String = "$scheme://$host" + (if (port == -1) "" else ":$port")
}
