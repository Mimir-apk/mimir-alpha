package com.gruning.mimir.rewrite.core.metadata

import java.util.Locale

/**
 * One production container-routing rule shared by the enricher and its corpus fixtures.
 *
 * Routing consumes scanner truth ([relativePath] first, then [displayName]); an opaque SAF document id is
 * only a legacy fallback and can never override a known scanner filename.
 */
internal object ContainerRoutingPolicy {
    enum class Route {
        OPTICAL_ZIP_UNSUPPORTED,
        CHD,
        MAME_ZIP,
        MAME_LOOSE,
        SWITCH_NSP_PROVISIONAL,
        DEFAULT,
    }

    fun fileName(
        relativePath: String?,
        displayName: String?,
        legacyLocation: String?,
    ): String? {
        val candidates = sequenceOf(relativePath, displayName, legacyLocation)
        .mapNotNull { raw ->
            raw?.trim()
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.takeIf(String::isNotBlank)
        }
            .toList()
        // A SAF provider may persist an opaque relative leaf such as "42". Prefer a scanner/display
        // candidate that actually carries a suffix before falling back to that opaque but nonblank id.
        return candidates.firstOrNull(::hasFileExtension) ?: candidates.firstOrNull()
    }

    fun route(platformId: String, fileName: String?): Route {
        val extension = fileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            extension == "zip" && platformId in OPTICAL_PLATFORMS -> Route.OPTICAL_ZIP_UNSUPPORTED
            extension == "chd" -> Route.CHD
            platformId == "arcade" && extension == "zip" -> Route.MAME_ZIP
            platformId == "arcade" && extension !in MAME_CONTAINER_EXTENSIONS -> Route.MAME_LOOSE
            platformId == "switch" && extension == "nsp" -> Route.SWITCH_NSP_PROVISIONAL
            else -> Route.DEFAULT
        }
    }

    internal val MAME_CONTAINER_EXTENSIONS = setOf("7z", "chd", "rar", "zip")

    private fun hasFileExtension(value: String): Boolean =
        value.substringAfterLast('.', missingDelimiterValue = "").isNotBlank()

    /**
     * Both rewrite-v1 short ids and registry-canonical ids are deliberate. Existing databases can contain
     * either namespace, and packaging policy must not change with discovery vintage.
     */
    internal val OPTICAL_PLATFORMS = setOf(
        "3do",
        "atari_jaguar_cd",
        "commodore_cd32",
        "commodore_cdtv",
        "dreamcast",
        "gamecube",
        "nec_pc_engine_cd_turbografx_cd",
        "nec_pc_fx",
        "nintendo_wii",
        "nintendo_wii_u",
        "pcenginecd",
        "philips_cd_i",
        "ps1",
        "ps2",
        "ps3",
        "psp",
        "saturn",
        "segacd",
        "sega_mega_cd_sega_cd",
        "snk_neo_geo_cd",
        "the_3do_company_3do",
        "wii",
        "xbox",
        "xbox360",
    )
}
