package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.metadata.online.OnlinePlatformProvider
import com.gruning.mimir.rewrite.core.metadata.online.OnlineProviderPlatformProjection
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A platform Mimir can launch must also be a platform Mimir can fetch art for.
 *
 * The two authorities drift silently and in one direction only: adding an emulator seed is a
 * core-launch change, adding a ScreenScraper system id is a core-metadata change, and nothing
 * connected them. A platform that launches perfectly and can never fetch art produces no error, no
 * log line and no failing test -- every title on it simply keeps its silhouette forever, and the
 * gap is only discovered when someone happens to own a game for that system and says so.
 *
 * That is exactly how this was found. Resident Evil 5 on Xbox 360 had no artwork-provider mapping, which turned out to be three unmapped platforms; comparing
 * the two authorities properly then turned up seven more nobody had hit yet. This test exists so
 * the next one fails here instead of waiting for a player to notice.
 */
class SeededPlatformsAreScrapableTest {

    /**
     * Platforms deliberately exempt from ScreenScraper's closure specifically, by kind rather than by
     * oversight or by gap: ScreenScraper matches ROM files by hash/name, and these are not ROMs.
     * "pc_games" (R-2b, GameNative) is a synthetic Mimir-native platform whose entries are shortcut
     * files GameNative itself manages -- ScreenScraper's system catalogue has no analogous "PC games via
     * GameNative" system to map to, so there is structurally no id to add here (unlike the seven real
     * consoles this test was written to catch).
     *
     * SteamGridDB supplies pc_games art
     * outright, keyed by the Steam app id embedded in a `.steam` shortcut's body (or by filename for the
     * other four extensions), reached from `CustomHeroStore`/`MimirAppCoordinator`, never from
     * ScreenScraper's system-id closure this projection audits. So pc_games genuinely reaches a
     * provider now -- just not the one this specific set is about, which is why it stays exempt HERE
     * rather than this test silently going green from a projection change it never made (trap: deleting
     * this entry would only be honest in the same commit that gives ScreenScraper itself a pc_games
     * system id, which R-24 explicitly declined).
     */
    private val ExemptFromScreenScraperClosure = setOf(PlatformId.PC_GAMES.value)

    @Test
    fun everyLaunchablePlatformCanAlsoBeScraped() {
        val scrapable = OnlineProviderPlatformProjection.rows()
            .filter { it.provider == OnlinePlatformProvider.SCREEN_SCRAPER }
            // rawPlatformId, not the canonical target: ScreenScraperSystemMap.systemeIdFor keys on
            // the map's own key, so an aliased entry like "mastersystem" (declaring canonical
            // "sega_master_system_mark_iii") is reachable only by the key a seed actually carries.
            // Matching the canonical instead reports every aliased platform as a false gap.
            .map { it.rawPlatformId }
            .toSet()

        val unscrapable = EmulatorSeedRegistry.allSeedPlatforms()
            .map { it.value }
            .filterNot { it in scrapable || it in ExemptFromScreenScraperClosure }
            .sorted()

        assertEquals(
            "These platforms have an emulator seed but no ScreenScraper system id, so every game " +
                "on them launches and then keeps its silhouette forever. Add the id to " +
                "ScreenScraperSystemMap.byPlatform -- read it live from systemesListe.php, never " +
                "guess: adjacent ids (Xbox 32 / Xbox 360 33) return a real cover for the wrong " +
                "console rather than failing honestly.",
            emptyList<String>(),
            unscrapable,
        )
    }
}
