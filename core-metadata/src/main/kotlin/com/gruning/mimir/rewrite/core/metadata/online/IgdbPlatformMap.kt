package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Mimir `PlatformId` → IGDB numeric `platforms` id ([ScreenScraperSystemMap] precedent, same "best-effort,
 * confirm any doubtful id live" posture).
 *
 * OWED (R6-P3-01, policy 2026-09-15): only `pc_games -> 6` is docs-grounded (IGDB's own Apicalypse
 * examples name PC = 6). Every ROM platform id in the spec's starter list is HYPOTHESIS — Twitch credentials were not available to run the one `platforms` verification query
 * (`fields id,name,abbreviation; limit 500;` + `offset 500;`) this map's own precedent
 * ([ScreenScraperSystemMap]'s "a one-off id would have returned a real cover for the wrong system" note)
 * says never to skip. Deliberately left OUT of this map until that query is run and its response retained — an unverified id here would not fail loud, it would silently attach the
 * wrong game's synopsis to a ROM row. Every ROM platform therefore reads [igdbIdFor] as `null` today,
 * which [IgdbMetadataSource] turns into `PlatformUnsupported` (no request, nothing learned) — never a
 * guess. Adding the verified ids later is a pure data change to [byPlatform], nothing else.
 */
internal object IgdbPlatformMap {

    private val byPlatform: Map<String, Int> = mapOf(
        "pc_games" to 6,
    )

    /** The IGDB platforms id for [platformId], or null when unmapped (→ PlatformUnsupported, no request). */
    fun igdbIdFor(platformId: PlatformId): Int? = byPlatform[platformId.value.lowercase()]

    internal fun platformAuthorityRows(): List<OnlineProviderPlatformRow> =
        byPlatform.map { (rawPlatformId, igdbId) ->
            OnlineProviderPlatformRow(
                provider = OnlinePlatformProvider.IGDB,
                rawPlatformId = rawPlatformId,
                declaredCanonicalPlatformId = rawPlatformId,
                providerKey = igdbId.toString(),
            )
        }.sortedWith(
            compareBy(
                { it.declaredCanonicalPlatformId },
                { it.rawPlatformId },
                { it.providerKey },
            ),
        )
}
