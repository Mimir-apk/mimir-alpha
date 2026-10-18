package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.model.PlatformId

enum class OnlinePlatformProvider {
    RETRO_ACHIEVEMENTS,
    SCREEN_SCRAPER,
    IGDB,
}

/**
 * Primitive, immutable audit projection. It exposes platform identity facts only—never clients,
 * credentials, consent, transport state, mutable provider state, or provider responses.
 */
data class OnlineProviderPlatformRow(
    val provider: OnlinePlatformProvider,
    val rawPlatformId: String,
    val declaredCanonicalPlatformId: String,
    val providerKey: String,
)

object OnlineProviderPlatformProjection {
    fun rows(): List<OnlineProviderPlatformRow> =
        (
            RetroAchievementsConsoleMap.platformAuthorityRows() +
                ScreenScraperSystemMap.platformAuthorityRows() +
                IgdbPlatformMap.platformAuthorityRows()
            )
            .distinct()
            .sortedWith(
                compareBy(
                    { it.provider.name },
                    { it.declaredCanonicalPlatformId },
                    { it.rawPlatformId },
                    { it.providerKey },
                ),
            )

    fun retroAchievementsSupports(platformId: String): Boolean =
        RetroAchievementsConsoleMap.supportsPlatform(platformId)

    fun screenScraperSystemId(platformId: String): Int? =
        ScreenScraperSystemMap.systemeIdFor(PlatformId(platformId))

    fun igdbId(platformId: String): Int? =
        IgdbPlatformMap.igdbIdFor(PlatformId(platformId))
}
