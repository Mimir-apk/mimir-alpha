package com.gruning.mimir.rewrite.core.metadata.online

/**
 * The few shapes of RetroAchievements media Mimir will ever fetch, and the one host it may be fetched from.
 *
 * Provider responses carry RELATIVE image paths: a game's `ImageIcon`, `ImageTitle` and `ImageIngame` (`/Images/<digits>.png`) and each achievement's
 * `BadgeName` (digits only, served as `/Badge/<name>.png` earned and `/Badge/<name>_lock.png` locked). They are provider values, so they are trusted for
 * nothing but those exact shapes. An absolute URL, a path with a query, a fragment, a traversal, a different extension, a non-numeric badge name or another
 * host is rejected, never rewritten, so a response can never steer a request to a host or file of its choosing. The URL built from a valid path carries no
 * credential and no query; nothing here ever touches a log.
 */
object RetroAchievementsIcons {
    /** RA's public media host, the only host media is ever fetched from. */
    const val MEDIA_HOST = "media.retroachievements.org"

    /** A game image or a badge is a PNG of a few kilobytes; anything past this is not one. */
    const val MAX_BYTES: Long = 256L * 1024L

    private val imagePathShape = Regex("/Images/[0-9]{1,12}[.]png")
    private val badgePathShape = Regex("/Badge/[0-9]{1,12}(_lock)?[.]png")
    private val badgeNameShape = Regex("[0-9]{1,12}")

    /** [raw] when it is exactly a relative `/Images/<digits>.png`, otherwise null. */
    fun validatedPath(raw: String?): String? = raw?.takeIf { imagePathShape.matches(it) }

    /** [raw] when it is a badge name (digits only), otherwise null. */
    fun validatedBadgeName(raw: String?): String? = raw?.takeIf { badgeNameShape.matches(it) }

    /** The relative path of a badge: `/Badge/<name>.png` earned, `/Badge/<name>_lock.png` locked. Null when [badgeName] is not digits only. */
    fun badgePath(badgeName: String?, locked: Boolean): String? =
        validatedBadgeName(badgeName)?.let { if (locked) "/Badge/${it}_lock.png" else "/Badge/$it.png" }

    /** [raw] when it is any media path Mimir fetches (a game image or a badge), otherwise null. */
    fun validatedMediaPath(raw: String?): String? = raw?.takeIf { imagePathShape.matches(it) || badgePathShape.matches(it) }

    /** The HTTPS URL for a validated media [path] (no credential, no query), or null when [path] is not valid. */
    fun url(path: String?): String? = validatedMediaPath(path)?.let { "https://$MEDIA_HOST$it" }

    /**
     * The file name a media file is kept under on disk, or null for an invalid path. A game image is `<digits>.png`; a badge is `badge_<name>.png` or
     * `badge_<name>_lock.png`, so no badge can ever overwrite a game image.
     */
    fun fileName(path: String?): String? {
        val valid = validatedMediaPath(path) ?: return null
        val name = valid.substringAfterLast('/')
        return if (valid.startsWith("/Badge/")) "badge_$name" else name
    }
}

/** A PNG starts with this signature; a body that does not is not an icon, whatever its URL said. */
internal fun looksLikeRetroAchievementsPng(bytes: ByteArray): Boolean =
    bytes.size > 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() && bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
