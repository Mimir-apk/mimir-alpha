package com.gruning.mimir.rewrite.core.metadata.online

import com.gruning.mimir.rewrite.core.metadata.ContentHash
import com.gruning.mimir.rewrite.core.metadata.MetadataOrigin
import com.gruning.mimir.rewrite.core.metadata.MetadataQuery
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ScreenScraperClientTest {

    private val dev = DevAuth(devId = "D", devPassword = "P", softName = "MimirApk")

    // ── URL building ──
    @Test fun userInfoUrl_carries_dev_and_user_and_json() {
        val url = ScreenScraperApi.userInfoUrl(dev, UserAuth(id = "alice", password = "pw"))
        assertTrue(url.startsWith("https://www.screenscraper.fr/api2/ssuserInfos.php?"))
        assertTrue(url.contains("devid=D"))
        assertTrue(url.contains("devpassword=P"))
        assertTrue(url.contains("softname=MimirApk"))
        assertTrue(url.contains("output=json"))
        assertTrue(url.contains("ssid=alice") && url.contains("sspassword=pw"))
    }

    @Test fun userAuth_string_form_redacts_the_password() {
        val auth = UserAuth(id = "alice", password = "never-print-this")
        assertTrue(auth.toString().contains("alice"))
        assertTrue(!auth.toString().contains("never-print-this"))
    }

    @Test fun developerAuth_string_form_redacts_registration_secrets() {
        val auth = DevAuth(
            devId = "never-print-id",
            devPassword = "never-print-password",
            softName = "MimirApk",
        )
        assertTrue(auth.toString().contains("MimirApk"))
        assertTrue(!auth.toString().contains("never-print-id"))
        assertTrue(!auth.toString().contains("never-print-password"))
    }

    @Test fun gameInfoUrl_is_hashfirst_and_omits_blank_user() {
        val url = ScreenScraperApi.gameInfoUrl(
            dev, user = null, systemeId = 23,
            hash = ContentHash(crc32 = "abcd1234", sizeBytes = 1000L),
            romName = "Sonic Adventure (USA)",
        )
        assertTrue(url.contains("systemeid=23"))
        assertTrue(url.contains("crc=abcd1234"))
        assertTrue(url.contains("romtaille=1000"))
        assertTrue(url.contains("romnom=Sonic+Adventure")) // cleaned (region tag stripped) + URL-encoded
        assertTrue(!url.contains("%28USA%29"))              // the "(USA)" tag is stripped before lookup
        assertTrue(!url.contains("ssid="))                            // no blank user creds
    }

    @Test fun systemMediaUrl_is_bounded_png_and_keeps_the_canonical_media_key() {
        val url = ScreenScraperApi.systemMediaUrl(
            dev,
            UserAuth(id = "alice", password = "pw"),
            systemeId = 29,
            media = "wheel(wor)",
        )
        assertTrue(url.startsWith("https://www.screenscraper.fr/api2/mediaSysteme.php?"))
        assertTrue(url.contains("systemeid=29"))
        assertTrue(url.contains("media=wheel%28wor%29"))
        assertTrue(url.contains("maxwidth=1024") && url.contains("maxheight=1024"))
        assertTrue(url.contains("outputformat=png"))
    }

    // ── Response parsing ──
    @Test fun parseUserQuota_reads_live_caps() {
        val q = ScreenScraperApi.parseUserQuota(
            """{"response":{"ssuser":{"maxthreads":"4","maxrequestsperday":"20000","requeststoday":"12"}}}""",
        )
        assertEquals(4, q?.maxThreads)
        assertEquals(20000, q?.maxRequestsPerDay)
        assertEquals(12, q?.requestsToday)
    }

    @Test fun parseUserQuota_null_on_closed_api_plaintext() {
        assertNull(ScreenScraperApi.parseUserQuota("API fermé pour les non membres"))
    }

    @Test fun parseMedias_extracts_typed_regioned_entries() {
        val medias = ScreenScraperApi.parseMedias(SAMPLE_GAME)
        assertEquals(3, medias.size)
        assertTrue(medias.any { it.type == "box-2D" && it.region == "wor" })
    }

    @Test fun parseSynopsis_uses_only_the_explicit_language_order_and_normalizes_copy() {
        assertEquals(
            "Une aventure rapide.",
            ScreenScraperApi.parseSynopsis(SYNOPSIS_GAME, listOf("fr", "en")),
        )
        assertEquals(
            "A fast adventure across the world.",
            ScreenScraperApi.parseSynopsis(SYNOPSIS_GAME, listOf("de", "en")),
        )
    }

    @Test fun parseSynopsis_does_not_fall_into_an_unrequested_language() {
        assertNull(ScreenScraperApi.parseSynopsis(SYNOPSIS_GAME, listOf("de")))
        assertNull(
            ScreenScraperApi.parseSynopsis(
                """{"response":{"jeu":{"synopsis":{"synopsis_fr":"Copie française."}}}}""",
                listOf("en"),
            ),
        )
        assertNull(ScreenScraperApi.parseSynopsis("{}", listOf("en")))
        assertNull(ScreenScraperApi.parseSynopsis("not json", listOf("en")))
    }

    @Test fun parseCanonicalTitle_uses_only_the_explicit_english_region_ladder() {
        assertEquals(
            "Resident Evil",
            ScreenScraperApi.parseCanonicalTitle(
                """{"response":{"jeu":{"nom":"Biohazard","noms":{"nom_ss":"Biohazard","nom_us":"Resident Evil"}}}}""",
                listOf("us", "wor"),
            ),
        )
        assertNull(
            ScreenScraperApi.parseCanonicalTitle(
                """{"response":{"jeu":{"nom":"Biohazard","noms":{"nom_ss":"Biohazard"}}}}""",
                listOf("us"),
            ),
        )
        assertEquals(
            "Biohazard",
            ScreenScraperApi.parseProviderIdentityTitle(
                """{"response":{"jeu":{"nom":"Biohazard","noms":{"nom_ss":"Biohazard"}}}}""",
                listOf("us"),
            ),
        )
    }

    @Test fun canonicalTitleMatch_rejects_same_system_neighbouring_game() {
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Corpse Party - Book of Shadows", "Corpse Party - Book of Shadows"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Corpse Party", "Corpse Party - Book of Shadows"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Final Fantasy IV", "Final Fantasy III"))
        assertTrue(
            !ScreenScraperApi.canonicalTitleMatches(
                "Shin Megami Tensei Devil Summoner Soul Hackers",
                "Shin Megami Tensei Devil Summoner Soul Hackers 2",
            ),
        )
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Final Fantasy X", "Final Fantasy IX"))
    }

    @Test fun canonicalTitleMatch_allows_only_explicit_edition_noise() {
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Resident Evil 2 - DualShock", "Resident Evil 2"))
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Pokémon Dash", "Pokemon Dash"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Resident Evil 2 - Nemesis", "Resident Evil 2"))
    }

    @Test fun canonicalTitleMatch_accepts_catalogue_sorted_article_without_weakening_subtitles() {
        assertTrue(
            ScreenScraperApi.canonicalTitleMatches(
                "Legend of Zelda, The - The Minish Cap (USA)",
                "The Legend of Zelda : The Minish Cap",
            ),
        )
        assertTrue(
            !ScreenScraperApi.canonicalTitleMatches(
                "Legend of Zelda, The - The Minish Cap",
                "The Legend of Zelda : A Link to the Past",
            ),
        )
    }

    @Test fun canonicalTitleMatch_allows_only_leading_disneys_licensed_brand_alias() {
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney's Aladdin"))
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Disney's Aladdin", "Aladdin"))
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney\u2019s Aladdin"))
        assertTrue(ScreenScraperApi.canonicalTitleMatches("Disney\u2019s Aladdin", "Aladdin"))

        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney's Aladdin 2"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney's Aladdin - Nasira's Revenge"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney's The Jungle Book"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Capcom's Aladdin"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Something Disney's Aladdin"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney's"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Disney's", "Aladdin"))
        assertTrue(!ScreenScraperApi.canonicalTitleMatches("Aladdin", "Disney Aladdin"))
    }

    @Test fun parseSynopsis_prefers_rom_specific_copy_when_present() {
        val body = """{"response":{"jeu":{"synopsis":{"synopsis_en":"General copy"},"rom":{"synopsis":{"synopsis_en":"ROM copy"}}}}}"""
        assertEquals("ROM copy", ScreenScraperApi.parseSynopsis(body, listOf("en")))
    }

    @Test fun liveArrayShapes_returnRegionalTitleAndPreferredSynopsis() {
        val body = """
            {"response":{"jeu":{"noms":[
              {"region":"ss","text":"Legend Of Zelda - The Minish Cap, The"},
              {"region":"us","text":"The Legend of Zelda : The Minish Cap"}
            ],"synopsis":[
              {"langue":"fr","text":"Copie française."},
              {"langue":"en","text":"Link returns in an all-new adventure."}
            ]}}}
        """.trimIndent()

        assertEquals(
            "The Legend of Zelda : The Minish Cap",
            ScreenScraperApi.parseCanonicalTitle(body, listOf("us", "wor", "ss")),
        )
        assertEquals(
            "Link returns in an all-new adventure.",
            ScreenScraperApi.parseSynopsis(body, listOf("en")),
        )
        assertEquals("Copie française.", ScreenScraperApi.parseSynopsis(body, listOf("fr", "en")))
    }

    // the LOOKUP name must be cleaned or ScreenScraper 404s on the "NNNN - " set-number prefix.
    @Test fun cleanRomName_strips_setnumber_prefix_and_tag_groups() {
        assertEquals("Final Fantasy IV", ScreenScraperApi.cleanRomName("2623 - Final Fantasy IV (Europe) (En,Fr,De,Es,It)"))
        assertEquals("Pokemon Dash", ScreenScraperApi.cleanRomName("0119 - Pokemon Dash (Europe) (En,Fr,De,Es,It)"))
        assertEquals("Pokemon - Schwarze Edition", ScreenScraperApi.cleanRomName("Pokemon - Schwarze Edition (Germany) (NDSi Enhanced) [b]"))
        // A dot in the real title must survive (extension-stripping is the caller's job).
        assertEquals("Super Mario Bros. 3", ScreenScraperApi.cleanRomName("Super Mario Bros. 3"))
        assertEquals("Metroid Prime Pinball", ScreenScraperApi.cleanRomName("Metroid Prime Pinball (USA)"))
    }

    @Test fun parseErrorMessage_reads_plaintext_close_notice() {
        assertEquals("API fermé pour les non membres", ScreenScraperApi.parseErrorMessage("API fermé pour les non membres"))
    }

    // ── Media priority ──
    @Test fun resolver_prefers_box2D_world_region() {
        val url = ArtMediaResolver.pickBoxArtUrl(ScreenScraperApi.parseMedias(SAMPLE_GAME))
        assertEquals("https://www.screenscraper.fr/box2d-wor.png", url)
    }

    @Test fun resolver_keeps_support2D_out_of_the_box_slot() {
        val medias = listOf(ScreenScraperApi.Media("support-2D", "https://www.screenscraper.fr/support.png", "us", "png"))
        assertNull(ArtMediaResolver.pickBoxArtUrl(medias))
        assertEquals("https://www.screenscraper.fr/support.png", ArtMediaResolver.pickPhysicalMediaUrl(medias))
    }

    /**
     * COVER slot,
     *
     * A title whose provider record carries no `box-2D` and no `box-3D` used to get NO cover at all. It now
     * falls back to the flattened `box-texture` wrapper, which is box art being used as box art.
     *
     * This does not reopen the 2026-08-22 narrowing, and the test directly above
     * (`resolver_physical_media_never_substitutes_box_texture_when_no_support_media_exists`) is the pin that
     * proves it: that ruling was about the DISC slot, where a box wrapper filed as a disc scan is a lie
     * about what the image is. Both pins must hold at once -- same media list, opposite answers per slot.
     */
    @Test fun resolver_cover_falls_back_to_box_texture_when_no_box_render_exists() {
        val medias = listOf(ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/boxtex.png", "wor", "png"))
        assertEquals("https://www.screenscraper.fr/boxtex.png", ArtMediaResolver.pickBoxArtUrl(medias))
        // The same list, in the disc slot, still answers null -- the 08-22 ruling is untouched.
        assertNull(ArtMediaResolver.pickPhysicalMediaUrl(medias))
    }

    /**
     * `box-texture` ranks LAST: a real box render always wins, whatever order the provider listed them in.
     *
     * Honest about its own strength -- this is a RANKING guard, not a differential pin. It passes both
     * before and after the 2026-08-23 change (with `box-texture` absent from the ladder, `box-3D` wins
     * trivially). What it does catch is the future edit that admits `box-texture` at the WRONG rank,
     * which would silently prefer a flattened wrapper over a real box render. The differential pin for
     * the change itself is the test above.
     */
    @Test fun resolver_cover_prefers_a_real_box_render_over_box_texture() {
        val medias = listOf(
            ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/boxtex.png", "wor", "png"),
            ScreenScraperApi.Media("box-3D", "https://www.screenscraper.fr/box3d.png", "wor", "png"),
        )
        assertEquals("https://www.screenscraper.fr/box3d.png", ArtMediaResolver.pickBoxArtUrl(medias))
    }

    // A genuine support-* scan is what this slot is for. box-texture is NOT admitted here at all any more
    // (see the next test and ArtMediaResolver's file doc) -- this pins that a real scan wins regardless.
    @Test fun resolver_physical_media_prefers_real_support_scan_over_box_texture_fallback() {
        val medias = listOf(
            // box-texture appears first in the list; a genuine support-2D scan must still win.
            ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/boxtex.png", "wor", "png"),
            ScreenScraperApi.Media("support-2D", "https://www.screenscraper.fr/support.png", "us", "png"),
        )
        assertEquals("https://www.screenscraper.fr/support.png", ArtMediaResolver.pickPhysicalMediaUrl(medias))
    }

    /**
     * The AUTOMATIC enrichment slot never substitutes box art for a disc/cartridge scan.
     *
     * The box-texture fallback is limited to the explicit Customize Media picker, where it is
     * a labelled candidate ("Box texture (fallback)") the player chooses. Commit 5b9ef36d also widened
     * it to this automatic path, which installs
     * the winner silently with no label and no choice. Device evidence 2026-08-22: that widening wrote a
     * flattened Xbox 360 box wrapper (1024x680) into Resident Evil 5's disc slot -- the only wrong file of
     * 21 physical-media files on the sampled SD card, and the regression report described it as missing disc art.
     *
     * The picker's two sites (ScreenScraperMetadataSource.PHYSICAL_MEDIA_TYPES, app/CustomHeroStore.kt's
     * CustomPhysicalScreenScraperTypes) still admit box-texture and are deliberately untouched.
     */
    @Test fun resolver_physical_media_never_substitutes_box_texture_when_no_support_media_exists() {
        val medias = listOf(ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/boxtex.png", "wor", "png"))
        assertNull(ArtMediaResolver.pickPhysicalMediaUrl(medias))
    }

    /**
     * The explicit-picker boundary, stated as a test.
     *
     * Box art reaches the Hero through the PICKER, where the player sees a labelled candidate and chooses
     * it (`app/CustomHeroStore.kt`'s `CustomHeroScreenScraperTypes`, widened that day). This constant is
     * the other thing: the AUTOMATIC enrichment ranking, which installs a backdrop with no label and no
     * choice. That is precisely the silent substitution the dup-cover ban exists for, so it stays
     * box-free and a box-only title honestly gets no automatic hero at all.
     *
     * If a future change makes this test go green by admitting box art here, the ban is gone -- not
     * scoped. Preserve the explicit-picker boundary.
     */
    @Test fun resolver_automatic_hero_never_substitutes_box_art_for_a_backdrop() {
        val medias = listOf(
            ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/boxtex.png", "wor", "png"),
            ScreenScraperApi.Media("box-3D", "https://www.screenscraper.fr/box3d.png", "wor", "png"),
            ScreenScraperApi.Media("box-2D", "https://www.screenscraper.fr/box2d.png", "wor", "png"),
        )
        assertNull(ArtMediaResolver.pickHeroArtUrl(medias))
        // The same list DOES answer in the cover slot -- that is the 2026-08-23 cover half. It answers
        // with the 3D box since 2026-08-25 (C6), which is the only part of this assertion that moved.
        assertEquals("https://www.screenscraper.fr/box3d.png", ArtMediaResolver.pickBoxArtUrl(medias))
    }

    /**
     * Live probes found both box-2D and box-3D on every GameCube and Wii title checked. Previously
     * the automatic ladder could not reach those 3D boxes and only the explicit picker offered them.
     * Preferring box-3D makes automatic enrichment usable without per-game picker adjustments.
     */
    @Test fun resolver_cover_leads_with_the_3D_box() {
        val medias = listOf(
            ScreenScraperApi.Media("box-2D", "https://www.screenscraper.fr/b2.png", "wor", "png"),
            ScreenScraperApi.Media("box-3D", "https://www.screenscraper.fr/b3.png", "us", "png"),
            ScreenScraperApi.Media("box-texture", "https://www.screenscraper.fr/btex.png", "wor", "png"),
        )
        assertEquals("https://www.screenscraper.fr/b3.png", ArtMediaResolver.pickBoxArtUrl(medias))

        // TYPE outranks REGION, and that is what keeps the type-first ranking stable. box-3D publishes no "wor" at all while
        // DEFAULT_REGIONS leads with it, so a region-FIRST ladder would have quietly handed the 2D box
        // back. The assertion above already proves the walk is type-first: the winner is the `us` 3D box
        // even though a `wor` 2D box was available, and `wor` outranks `us` in DEFAULT_REGIONS. That is
        // the exact shape the region ladder was not designed for, checked rather than assumed.

        // A 3D box with none of the default regions still wins its type, via pick's "first of the winning
        // type" clause -- so a de/fr/uk-only title gets a 3D box rather than dropping to 2D.
        val awkwardRegions = listOf(
            ScreenScraperApi.Media("box-2D", "https://www.screenscraper.fr/b2.png", "wor", "png"),
            ScreenScraperApi.Media("box-3D", "https://www.screenscraper.fr/b3-de.png", "de", "png"),
        )
        assertEquals("https://www.screenscraper.fr/b3-de.png", ArtMediaResolver.pickBoxArtUrl(awkwardRegions))

        // And the fallback order below it is unchanged: no 3D, take the 2D; neither, take the texture.
        assertEquals(
            "https://www.screenscraper.fr/b2.png",
            ArtMediaResolver.pickBoxArtUrl(medias.filterNot { it.type == "box-3D" }),
        )
        assertEquals(
            "https://www.screenscraper.fr/btex.png",
            ArtMediaResolver.pickBoxArtUrl(medias.filter { it.type == "box-texture" }),
        )
    }

    @Test fun resolver_null_when_nothing_matches() {
        val medias = listOf(ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v.mp4", "wor", "mp4"))
        assertNull(ArtMediaResolver.pickBoxArtUrl(medias))
    }

    @Test fun resolver_logo_uses_committed_type_priority_and_fallbacks() {
        val priority = listOf("wheel-hd", "wheel")
        val mediasByPriority = priority.map { type ->
            ScreenScraperApi.Media(type, "https://www.screenscraper.fr/$type.png", "wor", "png")
        }

        priority.indices.forEach { firstAvailable ->
            val candidates = mediasByPriority.drop(firstAvailable).reversed()
            assertEquals(
                "first available type should be ${priority[firstAvailable]}",
                mediasByPriority[firstAvailable].url,
                ArtMediaResolver.pickLogoUrl(candidates),
            )
        }
    }

    /** A logo slot never accepts screenshots, fan art or title screens. */
    @Test fun resolver_logo_never_stands_a_backdrop_or_screenshot_in_for_a_clear_logo() {
        val medias = listOf("fanart", "sstitle", "ss").map { type ->
            ScreenScraperApi.Media(type, "https://www.screenscraper.fr/$type.png", "wor", "png")
        }
        assertEquals(null, ArtMediaResolver.pickLogoUrl(medias))
    }

    @Test fun resolver_logo_honors_region_priority_and_ignores_case() {
        val medias = listOf(
            ScreenScraperApi.Media("WhEeL-Hd", "https://www.screenscraper.fr/eu.png", "EU", "png"),
            ScreenScraperApi.Media("WHEEL-HD", "https://www.screenscraper.fr/wor.png", "WoR", "png"),
        )

        assertEquals("https://www.screenscraper.fr/wor.png", ArtMediaResolver.pickLogoUrl(medias))
        assertEquals(
            "https://www.screenscraper.fr/eu.png",
            ArtMediaResolver.pickLogoUrl(medias, regionPriority = listOf("eu", "wor")),
        )
    }

    // CPL01 reversed (policy 2026-08-11, ArtMediaResolver.kt:22): device reports showed title
    // screens where a backdrop was expected, so the original title-screen priority was reversed —
    // fanart (the purpose-built backdrop) now wins, sstitle/ss are the honest fallbacks. This test used to
    // assert sstitle beat fanart; it now asserts the reverse on purpose.
    @Test fun resolver_hero_prefers_fanart_over_title_screen_and_never_uses_a_clear_logo() {
        val medias = listOf(
            ScreenScraperApi.Media("wheel-hd", "https://www.screenscraper.fr/logo.png", "wor", "png"),
            ScreenScraperApi.Media("sstitle", "https://www.screenscraper.fr/title.png", "wor", "png"),
            ScreenScraperApi.Media("fanart", "https://www.screenscraper.fr/fan.png", "wor", "png"),
        )

        assertEquals("https://www.screenscraper.fr/fan.png", ArtMediaResolver.pickHeroArtUrl(medias))
        assertNull(
            ArtMediaResolver.pickHeroArtUrl(
                listOf(ScreenScraperApi.Media("wheel-hd", "https://www.screenscraper.fr/logo.png", "wor", "png")),
            ),
        )
    }

    // CP05 video mode: prefer the original encode before the smaller normalized sibling.
    @Test fun resolver_video_prefers_original_before_normalized() {
        val medias = listOf(
            ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v.mp4", "wor", "mp4"),
            ScreenScraperApi.Media("video-normalized", "https://www.screenscraper.fr/vn.mp4", "wor", "mp4"),
        )
        assertEquals("https://www.screenscraper.fr/v.mp4", ArtMediaResolver.pickVideoUrl(medias))
    }

    @Test fun resolver_video_null_when_absent() {
        assertNull(ArtMediaResolver.pickVideoUrl(listOf(ScreenScraperApi.Media("box-2D", "https://x/b.png", "wor", "png"))))
    }

    // The launch sound's clip (clip priority): the small normalized sibling first, the original only as the
    // fallback. pickVideoUrl above keeps preferring the original; the two pickers differ on purpose.
    @Test fun sound_clip_prefers_normalized_over_the_original() {
        val medias = listOf(
            ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v.mp4", "wor", "mp4"),
            ScreenScraperApi.Media("video-normalized", "https://www.screenscraper.fr/vn.mp4", "wor", "mp4"),
        )
        assertEquals("https://www.screenscraper.fr/vn.mp4", ArtMediaResolver.pickSoundClipUrl(medias))
        assertEquals(
            "the order of the provider's list does not decide it",
            "https://www.screenscraper.fr/vn.mp4",
            ArtMediaResolver.pickSoundClipUrl(medias.reversed()),
        )
    }

    @Test fun sound_clip_falls_back_to_the_original_when_there_is_no_normalized_one() {
        val medias = listOf(
            ScreenScraperApi.Media("box-2D", "https://www.screenscraper.fr/b.png", "wor", "png"),
            ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v.mp4", "wor", "mp4"),
        )
        assertEquals("https://www.screenscraper.fr/v.mp4", ArtMediaResolver.pickSoundClipUrl(medias))
    }

    @Test fun sound_clip_null_when_the_provider_has_no_clip() {
        assertNull(ArtMediaResolver.pickSoundClipUrl(emptyList()))
        assertNull(
            ArtMediaResolver.pickSoundClipUrl(listOf(ScreenScraperApi.Media("box-2D", "https://x/b.png", "wor", "png"))),
        )
    }

    @Test fun sound_clip_region_preference_applies_inside_the_normalized_type() {
        val medias = listOf(
            ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v-wor.mp4", "wor", "mp4"),
            ScreenScraperApi.Media("video-normalized", "https://www.screenscraper.fr/vn-jp.mp4", "jp", "mp4"),
            ScreenScraperApi.Media("video-normalized", "https://www.screenscraper.fr/vn-us.mp4", "us", "mp4"),
        )
        assertEquals(
            "the type wins over the region, then the default region order picks within it (us before jp)",
            "https://www.screenscraper.fr/vn-us.mp4",
            ArtMediaResolver.pickSoundClipUrl(medias),
        )
        assertEquals(
            "a caller's own region order is honored inside the normalized type",
            "https://www.screenscraper.fr/vn-jp.mp4",
            ArtMediaResolver.pickSoundClipUrl(medias, regionPriority = listOf("jp", "us")),
        )
        assertEquals(
            "a normalized clip in no listed region still beats the original",
            "https://www.screenscraper.fr/vn-jp.mp4",
            ArtMediaResolver.pickSoundClipUrl(
                listOf(
                    ScreenScraperApi.Media("video", "https://www.screenscraper.fr/v-wor.mp4", "wor", "mp4"),
                    ScreenScraperApi.Media("video-normalized", "https://www.screenscraper.fr/vn-jp.mp4", "jp", "mp4"),
                ),
                regionPriority = listOf("wor", "us"),
            ),
        )
    }

    // ── System map ──
    @Test fun systemMap_maps_known_and_nulls_unknown() {
        assertEquals(23, ScreenScraperSystemMap.systemeIdFor(PlatformId("dreamcast")))
        assertEquals(61, ScreenScraperSystemMap.systemeIdFor(PlatformId("psp")))
        assertEquals(29, ScreenScraperSystemMap.systemeIdFor(PlatformId("3do")))
        assertEquals(
            29,
            ScreenScraperSystemMap.systemeIdFor(PlatformId("the_3do_company_3do")),
        )
        assertEquals(75, ScreenScraperSystemMap.systemeIdFor(PlatformId("mame")))
        assertNull(ScreenScraperSystemMap.systemeIdFor(PlatformId("nonexistent")))
    }

    @Test fun systemImageInputs_are_gate_and_platform_mapped_without_touching_network() {
        var called = false
        val source = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            userAuth = { UserAuth("alice", "pw") },
            httpGet = { called = true; null },
        )
        val inputs = source.systemImageInputs(PlatformId("3do"))
        assertEquals(4, inputs.size)
        assertTrue(inputs.all { it.contains("systemeid=29") })
        assertTrue(!called)

        val closed = ScreenScraperMetadataSource(gateOpen = { false }, devAuth = { dev })
        assertTrue(closed.systemImageInputs(PlatformId("3do")).isEmpty())
        assertTrue(source.systemImageInputs(PlatformId("nonexistent")).isEmpty())
    }

    // NDS library rows carry the canonical id "ds" (not "nds"); the map MUST key on "ds"
    // or every DS game silently fetches nothing.
    @Test fun systemMap_keys_on_canonical_ds_not_nds() {
        assertEquals(15, ScreenScraperSystemMap.systemeIdFor(PlatformId("ds")))
        assertEquals(15, ScreenScraperSystemMap.systemeIdFor(PlatformId("nds"))) // alias also resolves
    }

    // CP03 Batch 1: the newly-wired platforms the scanner already recognizes.
    @Test fun systemMap_covers_batch1_platforms() {
        assertEquals(2, ScreenScraperSystemMap.systemeIdFor(PlatformId("mastersystem")))
        assertEquals(21, ScreenScraperSystemMap.systemeIdFor(PlatformId("gamegear")))
        assertEquals(45, ScreenScraperSystemMap.systemeIdFor(PlatformId("wonderswan")))
        assertEquals(11, ScreenScraperSystemMap.systemeIdFor(PlatformId("virtualboy")))
    }

    // ── Source guards ──
    @Test fun lookup_returns_null_and_makes_no_request_when_gate_closed() = runBlocking {
        var called = false
        val src = ScreenScraperMetadataSource(
            gateOpen = { false },
            devAuth = { dev },
            httpGet = { called = true; SAMPLE_GAME },
        )
        assertNull(src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic")))
        assertTrue("gate closed must not touch the network", !called)
    }

    @Test fun lookup_returns_null_when_unconfigured() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { null }, httpGet = { SAMPLE_GAME })
        assertNull(src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic")))
    }

    // R6-P2-03 lever B: a persistently null body now costs 3 attempts + 2 backoffs (2s) before
    // Unreachable — runTest's virtual clock skips that delay instead of actually waiting on it.
    @Test fun lookupOutcome_keeps_transport_failure_distinct_from_no_match() = runTest {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { null })

        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Unreachable,
            src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic")),
        )
    }

    @Test fun lookupOutcome_keeps_provider_error_distinct_from_no_match() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { "API fermé pour les non membres" },
        )

        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.BUSY),
            src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), providerGameId = "1")),
        )
    }

    @Test fun lookupOutcome_reports_provider_declared_absence_as_empty() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { "Game not found" },
        )

        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Empty,
            src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), providerGameId = "404")),
        )
    }

    @Test fun lookupOutcome_reports_closed_gate_without_network() = runBlocking {
        var called = false
        val src = ScreenScraperMetadataSource(
            gateOpen = { false },
            devAuth = { dev },
            httpGet = { called = true; SAMPLE_GAME },
        )

        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.GateClosed,
            src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic")),
        )
        assertTrue(!called)
    }

    @Test fun lookup_maps_box_flat_art_and_video_when_open_and_matched() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { ALL_MEDIA_GAME })
        val rec = src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic Adventure"))
        assertEquals(MetadataOrigin.SCREENSCRAPER, rec?.origin)
        assertEquals("https://www.screenscraper.fr/box2d-wor.png", rec?.boxArtUri)
        assertEquals("https://www.screenscraper.fr/wheel-hd-wor.png", rec?.flatArtUri)
        assertEquals("https://www.screenscraper.fr/title-wor.png", rec?.heroArtUri)
        // Updated deliberately (was video.mp4): the record's clip URL feeds only the launch sound, and the sound prefers
        // the small normalized sibling so a large original cannot record a "too large" negative.
        assertEquals("https://www.screenscraper.fr/video-normalized.mp4", rec?.previewVideoUri)
    }

    @Test fun lookup_returns_logo_only_record() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { LOGO_ONLY_GAME })
        val rec = src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Homebrew"))

        assertEquals(MetadataOrigin.SCREENSCRAPER, rec?.origin)
        assertNull(rec?.boxArtUri)
        assertEquals("https://www.screenscraper.fr/wheel.png", rec?.flatArtUri)
    }

    @Test fun lookup_returns_box_only_record() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { SAMPLE_GAME })
        val rec = src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic Adventure"))

        assertEquals("https://www.screenscraper.fr/box2d-wor.png", rec?.boxArtUri)
        assertNull(rec?.flatArtUri)
        assertEquals("https://www.screenscraper.fr/support-wor.png", rec?.physicalMediaUri)
    }

    @Test fun lookup_returns_description_only_record_without_inventing_art() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { SYNOPSIS_GAME },
            preferredSynopsisLanguages = { listOf("en") },
        )
        val rec = src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Sonic Adventure"))

        assertEquals(MetadataOrigin.SCREENSCRAPER, rec?.origin)
        assertEquals("A fast adventure across the world.", rec?.description)
        assertNull(rec?.boxArtUri)
        assertNull(rec?.flatArtUri)
    }

    @Test fun gba_enrich_accepts_noIntroSortedArticle_and_returnsProviderSynopsis() = runBlocking {
        val body = """
            {"response":{"jeu":{"id":"4634","nom":"The Legend of Zelda : The Minish Cap",
            "systeme":{"id":"12"},"synopsis":{"synopsis_en":"Link restores the Minish sword."},
            "medias":[]}}}
        """.trimIndent()
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { body },
            preferredSynopsisLanguages = { listOf("en") },
        )

        val rec = src.lookup(
            MetadataQuery(
                platformId = PlatformId("gba"),
                rawBaseName = "Legend of Zelda, The - The Minish Cap (USA)",
            ),
        )

        // The provider's unlabelled internal title may prove identity, but it must not replace the
        // English/offline presentation title when no requested English-region name was returned.
        assertEquals("Legend of Zelda, The - The Minish Cap (USA)", rec?.canonicalTitle)
        assertEquals("Link restores the Minish sword.", rec?.description)
        assertEquals("4634", rec?.providerGameId)
    }

    @Test fun liveGbaEnrich_returnsAProviderSynopsis_whenExplicitlyEnabled() = runBlocking {
        assumeTrue("Live metadata proof is opt-in", System.getenv("MIMIR_CONNECTED_METADATA_LIVE") == "1")
        assumeTrue("Requires the untracked ScreenScraper software registration", ScreenScraperDevRegistration.isConfigured)
        var safeDiagnostic = "no provider response"
        val source = ScreenScraperMetadataSource(
            gateOpen = { true },
            // Deliberately no member credential: this host proof cannot read or copy a device account.
            userAuth = { null },
            preferredSynopsisLanguages = { listOf("en") },
            preferredNameRegions = { listOf("us", "wor", "eu", "ss") },
            httpGet = { credentialedUrl ->
                liveScreenScraperGet(credentialedUrl)?.also { body ->
                    val identity = ScreenScraperApi.parseGameIdentity(body)
                    val response = runCatching {
                        Json.parseToJsonElement(body).jsonObject["response"]?.jsonObject
                    }.getOrNull()
                    val game = response?.get("jeu")?.jsonObject
                    val jeuKeys = game?.keys?.sorted().orEmpty()
                    val synopsisEntryKeys = runCatching {
                        game?.get("synopsis")?.jsonArray?.firstOrNull()?.jsonObject?.keys?.sorted()
                    }.getOrNull().orEmpty()
                    val nameEntryKeys = runCatching {
                        game?.get("noms")?.jsonArray?.firstOrNull()?.jsonObject?.keys?.sorted()
                    }.getOrNull().orEmpty()
                    safeDiagnostic = buildString {
                        append("error=").append(ScreenScraperApi.parseErrorMessage(body))
                        append(", gameId=").append(identity?.gameId)
                        append(", systemId=").append(identity?.systemeId)
                        append(", title=").append(ScreenScraperApi.parseCanonicalTitle(body, listOf("us", "wor", "ss")))
                        append(", synopsis=").append(ScreenScraperApi.parseSynopsis(body, listOf("en")) != null)
                        append(", mediaCount=").append(ScreenScraperApi.parseMedias(body).size)
                        append(", responseKeys=").append(response?.keys?.sorted().orEmpty())
                        append(", gameKeys=").append(jeuKeys)
                        append(", synopsisEntryKeys=").append(synopsisEntryKeys)
                        append(", nameEntryKeys=").append(nameEntryKeys)
                    }
                }
            },
        )

        val outcome = source.lookupOutcome(
            MetadataQuery(
                platformId = PlatformId("gba"),
                rawBaseName = "Legend of Zelda, The - The Minish Cap (USA)",
                // ScreenScraper's public game id for this GBA record. The exact-id live rung proves
                // provider availability + synopsis parsing independently of fuzzy-name availability.
                providerGameId = "3751",
            ),
        )
        val loaded = outcome as? ScreenScraperMetadataSource.LookupOutcome.Loaded
        assertTrue(
            "Explicit GBA Enrich should resolve a real ScreenScraper record; got $outcome ($safeDiagnostic)",
            loaded != null,
        )
        assertTrue(
            "The real GBA provider record should include synopsis copy ($safeDiagnostic)",
            !loaded?.record?.description.isNullOrBlank(),
        )
    }

    /** Live-test-only copy of the production GET posture; never logs its credential-bearing input URL. */
    private suspend fun liveScreenScraperGet(credentialedUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = URL(credentialedUrl)
            val host = parsed.host.lowercase()
            if (parsed.protocol != "https" || (host != "screenscraper.fr" && !host.endsWith(".screenscraper.fr"))) {
                return@runCatching null
            }
            (parsed.openConnection() as HttpURLConnection).run {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "MimirApk")
                try {
                    val stream = if (responseCode in 200..299) inputStream else errorStream
                    stream?.let { readScreenScraperUtf8Bounded(it, 4L * 1024L * 1024L) }
                } finally {
                    disconnect()
                }
            }
        }.getOrNull()
    }

    @Test fun lookup_returns_null_for_irrelevant_media_only() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { IRRELEVANT_MEDIA_GAME },
        )

        assertNull(src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Unknown")))
    }

    /**
     * The launch sound needs the clip URL the lookup already returns, and nothing else about the game. A verified record
     * whose only usable media is a clip must therefore be KEPT (not skipped as empty, which would send the ladder on
     * to another rung), carrying just that URL: no image slot and no synopsis is invented for it.
     */
    @Test fun lookup_keeps_a_record_that_carries_only_a_clip() = runBlocking {
        var calls = 0
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = {
                calls++
                CLIP_ONLY_GAME
            },
        )

        val outcome = src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Unknown"))

        assertTrue("got $outcome", outcome is ScreenScraperMetadataSource.LookupOutcome.Loaded)
        val rec = (outcome as ScreenScraperMetadataSource.LookupOutcome.Loaded).record
        assertEquals(
            "the normalized sibling is preferred over the original encode, for the sound's sake",
            "https://www.screenscraper.fr/video-normalized.mp4",
            rec.previewVideoUri,
        )
        assertNull(rec.boxArtUri)
        assertNull(rec.flatArtUri)
        assertNull(rec.heroArtUri)
        assertNull(rec.physicalMediaUri)
        assertNull(rec.description)
        assertEquals("no extra request: the clip URL rides the lookup that was already made", 1, calls)
    }

    @Test fun lookup_with_no_clip_and_no_other_media_still_reports_no_match() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { IRRELEVANT_MEDIA_GAME })

        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Empty,
            src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Unknown")),
        )
    }

    @Test fun verifiedProviderId_is_terminal_when_exact_record_cannot_be_used() = runBlocking {
        var calls = 0
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = {
                calls++
                IRRELEVANT_MEDIA_GAME
            },
        )

        assertNull(
            src.lookup(
                MetadataQuery(
                    PlatformId("dreamcast"),
                    rawBaseName = "Sonic Adventure",
                    candidateNames = listOf("Sonic Adventure"),
                    providerGameId = "verified-1",
                ),
            ),
        )
        assertEquals(1, calls)
    }

    @Test fun sameSystem_fuzzyNeighbour_is_rejected_without_id_or_hash_evidence() = runBlocking {
        val wrong = """{"response":{"jeu":{"id":"5","nom":"Corpse Party - Book of Shadows","systeme":{"id":"23"},"medias":[{"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/wrong.png"}]}}}"""
        val src = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { wrong },
        )

        assertNull(src.lookup(MetadataQuery(PlatformId("dreamcast"), rawBaseName = "Corpse Party")))
    }

    @Test fun snes_aladdin_accepts_leading_disneys_alias_and_rejects_sequel() = runBlocking {
        val aladdin = """
            {"response":{"jeu":{"id":"2170","nom":"Disney's Aladdin","noms":{"nom_us":"Disney's Aladdin"},"systeme":{"id":"4"},
            "synopsis":{"synopsis_en":"A street rat finds a magic lamp."},
            "medias":[{"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/aladdin-box.png"}]}}}
        """.trimIndent()
        val sequel = """
            {"response":{"jeu":{"id":"2171","nom":"Disney's Aladdin 2","noms":{"nom_us":"Disney's Aladdin 2"},"systeme":{"id":"4"},
            "medias":[{"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/aladdin2-box.png"}]}}}
        """.trimIndent()

        val loadedSrc = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { aladdin },
            preferredSynopsisLanguages = { listOf("en") },
        )
        val loadedOutcome = loadedSrc.lookupOutcome(
            MetadataQuery(platformId = PlatformId("snes"), rawBaseName = "Aladdin"),
        )
        val loaded = loadedOutcome as? ScreenScraperMetadataSource.LookupOutcome.Loaded
        assertTrue("Aladdin should settle Loaded against Disney's Aladdin; got $loadedOutcome", loaded != null)
        assertEquals("2170", loaded?.record?.providerGameId)
        assertEquals("Disney's Aladdin", loaded?.record?.canonicalTitle)
        assertEquals("A street rat finds a magic lamp.", loaded?.record?.description)
        assertEquals("https://www.screenscraper.fr/aladdin-box.png", loaded?.record?.boxArtUri)

        val sequelSrc = ScreenScraperMetadataSource(
            gateOpen = { true },
            devAuth = { dev },
            httpGet = { sequel },
        )
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Empty,
            sequelSrc.lookupOutcome(
                MetadataQuery(platformId = PlatformId("snes"), rawBaseName = "Aladdin"),
            ),
        )
    }

    @Test fun validate_reads_quota_from_body() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true }, devAuth = { dev },
            httpGet = { """{"response":{"ssuser":{"maxthreads":"2","maxrequestsperday":"5000","requeststoday":"1"}}}""" },
        )
        assertEquals(2, src.validate(UserAuth("a", "b"))?.maxThreads)
    }

    // ── Sign-in classification (honest failure reasons) ──
    @Test fun classify_developer_error_maps_to_DeveloperRejected() {
        // Both the dev + member messages contain "identifiants"; "développeur" must win.
        assertEquals(
            SignInOutcome.DeveloperRejected,
            ScreenScraperApi.classifySignInError("Erreur de login : Vérifier vos identifiants développeur !"),
        )
    }

    @Test fun classify_member_error_maps_to_MemberRejected() {
        assertEquals(
            SignInOutcome.MemberRejected,
            ScreenScraperApi.classifySignInError("Erreur de login : Vérifier les identifiants utilisateurs !"),
        )
    }

    @Test fun classify_closed_api_maps_to_Busy() {
        assertEquals(SignInOutcome.Busy, ScreenScraperApi.classifySignInError("API fermé pour les non membres"))
    }

    @Test fun signIn_success_on_ssuser_body() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true }, devAuth = { dev },
            httpGet = { """{"response":{"ssuser":{"maxthreads":"1","maxrequestsperday":"100","requeststoday":"0"}}}""" },
        )
        val out = src.signIn(UserAuth("a", "b"))
        assertTrue(out is SignInOutcome.Success && out.quota.maxRequestsPerDay == 100)
    }

    @Test fun signIn_developerRejected_when_dev_key_bad() = runBlocking {
        // The real 403 body for a bad developer key — must NOT be reported as a member-sign-in failure.
        val src = ScreenScraperMetadataSource(
            gateOpen = { true }, devAuth = { dev },
            httpGet = { "Erreur de login : Vérifier vos identifiants développeur !" },
        )
        assertEquals(SignInOutcome.DeveloperRejected, src.signIn(UserAuth("member", "pw")))
    }

    // R6-P2-03 lever B: signIn's infra probe now retries a null body too (3 attempts + 2s of backoff)
    // before Unreachable — runTest's virtual clock skips it.
    @Test fun signIn_unreachable_when_no_body() = runTest {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { null })
        assertEquals(SignInOutcome.Unreachable, src.signIn(UserAuth("a", "b")))
    }

    @Test fun signIn_notConfigured_when_no_dev_registration() = runBlocking {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { null }, httpGet = { "x" })
        assertEquals(SignInOutcome.NotConfigured, src.signIn(UserAuth("a", "b")))
    }

    @Test fun infraInfoUrl_is_dev_only() {
        val url = ScreenScraperApi.infraInfoUrl(dev)
        assertTrue(url.contains("ssinfraInfos.php?"))
        assertTrue(url.contains("devid=D") && url.contains("softname=MimirApk"))
        assertTrue(!url.contains("ssid="))
    }

    @Test fun isDeveloperError_only_true_for_dev_message() {
        assertTrue(ScreenScraperApi.isDeveloperError("Erreur de login : Vérifier vos identifiants développeur !"))
        assertTrue(!ScreenScraperApi.isDeveloperError("""{"response":{"serveurs":{}}}"""))
        assertTrue(!ScreenScraperApi.isDeveloperError("Erreur de login : Vérifier les identifiants utilisateurs !"))
    }

    // Sign-in classification: ssuserInfos masks a bad DEV key as a member
    // error, so a bad dev key must classify as DeveloperRejected via the dev-only infra probe...
    @Test fun signIn_developerRejected_even_when_userInfo_blames_the_member() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true }, devAuth = { dev },
            httpGet = { url ->
                if (url.contains("ssinfraInfos")) "Erreur de login : Vérifier vos identifiants développeur !"
                else "Erreur de login : Vérifier les identifiants utilisateurs !" // the masking message
            },
        )
        assertEquals(SignInOutcome.DeveloperRejected, src.signIn(UserAuth("realMember", "realPw")))
    }

    // ...and only when the DEV key is genuinely accepted does a user error mean the member sign-in was wrong.
    @Test fun signIn_memberRejected_only_when_dev_key_is_accepted() = runBlocking {
        val src = ScreenScraperMetadataSource(
            gateOpen = { true }, devAuth = { dev },
            httpGet = { url ->
                if (url.contains("ssinfraInfos")) """{"response":{"serveurs":{}}}""" // dev key OK
                else "Erreur de login : Vérifier les identifiants utilisateurs !"      // member wrong
            },
        )
        assertEquals(SignInOutcome.MemberRejected, src.signIn(UserAuth("member", "wrong")))
    }

    private companion object {
        // ART02: every fixture carries the response's OWN system id (like the live API always does) —
        // 23 = dreamcast, matching what these tests query. The strict lookup rejects anything else.
        const val SAMPLE_GAME = """
        {"response":{"jeu":{"id":"1","nom":"Sonic Adventure","systeme":{"id":"23"},"medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/box2d-wor.png"},
          {"type":"box-2D","region":"us","format":"png","url":"https://www.screenscraper.fr/box2d-us.png"},
          {"type":"support-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/support-wor.png"}
        ]}}}
        """

        const val ALL_MEDIA_GAME = """
        {"response":{"jeu":{"id":"1","nom":"Sonic Adventure","systeme":{"id":"23"},"medias":[
          {"type":"box-2D","region":"wor","format":"png","url":"https://www.screenscraper.fr/box2d-wor.png"},
          {"type":"wheel","region":"wor","format":"png","url":"https://www.screenscraper.fr/wheel-wor.png"},
          {"type":"wheel-hd","region":"wor","format":"png","url":"https://www.screenscraper.fr/wheel-hd-wor.png"},
          {"type":"sstitle","region":"wor","format":"png","url":"https://www.screenscraper.fr/title-wor.png"},
          {"type":"video","region":"wor","format":"mp4","url":"https://www.screenscraper.fr/video.mp4"},
          {"type":"video-normalized","region":"wor","format":"mp4","url":"https://www.screenscraper.fr/video-normalized.mp4"}
        ]}}}
        """

        const val LOGO_ONLY_GAME = """
        {"response":{"jeu":{"id":"2","nom":"Homebrew","systeme":{"id":"23"},"medias":[
          {"type":"wheel","region":"wor","format":"png","url":"https://www.screenscraper.fr/wheel.png"}
        ]}}}
        """

        // Deliberately carries NO video. It used to, back when a clip was not something the fetch could use; since the
        // launch sound  a clip IS usable, so a record with one is no longer "irrelevant
        // media only" -- see CLIP_ONLY_GAME and lookup_keeps_a_record_that_carries_only_a_clip.
        const val IRRELEVANT_MEDIA_GAME = """
        {"response":{"jeu":{"id":"3","nom":"Unknown","systeme":{"id":"23"},"medias":[
          {"type":"mixrbv2","region":"wor","format":"png","url":"https://www.screenscraper.fr/mix.png"}
        ]}}}
        """

        const val CLIP_ONLY_GAME = """
        {"response":{"jeu":{"id":"6","nom":"Unknown","systeme":{"id":"23"},"medias":[
          {"type":"mixrbv2","region":"wor","format":"png","url":"https://www.screenscraper.fr/mix.png"},
          {"type":"video-normalized","region":"wor","format":"mp4","url":"https://www.screenscraper.fr/video-normalized.mp4"},
          {"type":"video","region":"wor","format":"mp4","url":"https://www.screenscraper.fr/video.mp4"}
        ]}}}
        """

        const val SYNOPSIS_GAME = """
        {"response":{"jeu":{"id":"4","nom":"Sonic Adventure","systeme":{"id":"23"},"synopsis":{
          "synopsis_fr":"  Une aventure\nrapide.  ",
          "synopsis_en":"A fast adventure   across the world."
        },"medias":[]}}}
        """
    }

    // combined49: the game-music card needs to say WHY the provider refused, so the source names it from the answer's own words.
    private suspend fun failureFor(body: String): ScreenScraperMetadataSource.LookupOutcome {
        val src = ScreenScraperMetadataSource(gateOpen = { true }, devAuth = { dev }, httpGet = { body })
        return src.lookupOutcome(MetadataQuery(PlatformId("dreamcast"), providerGameId = "1"))
    }

    @Test fun lookupOutcome_names_the_quota_the_member_the_build_and_the_busy_provider() = runBlocking {
        assertEquals(
            "the day's quota spent is a daily limit, not a refused sign-in",
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.DAILY_LIMIT),
            failureFor("""{"response":{"error":"Erreur : Votre quota de scrape est dépassé pour aujourd'hui !"}}"""),
        )
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.DAILY_LIMIT),
            failureFor("Votre quota de scrape est dépassé pour aujourd'hui !"),
        )
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.SIGN_IN_REFUSED),
            failureFor("Erreur de login : Vérifier les identifiants utilisateurs !"),
        )
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.BUILD_REFUSED),
            failureFor("Erreur de login : Vérifier les identifiants développeur !"),
        )
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.BUSY),
            failureFor("API fermé pour les non membres"),
        )
    }

    @Test fun an_error_that_names_no_layer_is_no_answer_never_a_sign_in_blame() = runBlocking {
        assertEquals(
            ScreenScraperMetadataSource.LookupOutcome.Failed(ScreenScraperLookupFailure.NO_ANSWER),
            failureFor("Erreur interne du serveur"),
        )
        // The sign-in classifier keeps its own safe default; only the lookup refuses to guess.
        assertEquals(SignInOutcome.MemberRejected, ScreenScraperApi.classifySignInError("Erreur interne du serveur"))
    }

    @Test fun a_quota_body_still_reads_as_a_member_rejection_to_the_sign_in_classifier_it_reuses() {
        // classifyLookupError reuses classifySignInError's layers and adds the one a sign-in never meets; sign-in itself is unchanged.
        assertEquals(SignInOutcome.MemberRejected, ScreenScraperApi.classifySignInError("Votre quota de scrape est dépassé pour aujourd'hui !"))
        assertEquals(ScreenScraperLookupFailure.DAILY_LIMIT, ScreenScraperApi.classifyLookupError("Votre quota de scrape est dépassé pour aujourd'hui !"))
    }
}
