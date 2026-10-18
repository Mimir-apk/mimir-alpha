package com.gruning.mimir.rewrite

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import com.gruning.mimir.rewrite.core.launch.GameNativeLaunchRoute
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UXP-01 Custom Hero candidate model: provider/type labelling, the download host allowlist, the
 * ScreenScraper media filter, and credential redaction.
 */
class CustomHeroCandidateTest {
    @After
    fun resetGates() {
        MimirSteamGridDbGate.setOpen(false)
        MimirConnectedArtGate.setOpen(false)
    }

    @Test
    fun `manual preview generation preserves physical and provider pages together`() {
        assertEquals("manual-7-", customHeroPreviewGenerationPrefix("manual-7-physical"))
        assertEquals("manual-7-", customHeroPreviewGenerationPrefix("manual-7-hero-sgdb-p0"))
        assertEquals("manual-7-", customHeroPreviewGenerationPrefix("manual-7-hero-ss-p0"))
        assertTrue(!"manual-70-hero-sgdb-p0".startsWith(customHeroPreviewGenerationPrefix("manual-7-physical")))
    }


    // ── Provider host allowlisting (review blocker #3) ────────────────────────────────────────────

    @Test
    fun `each provider may only be downloaded from its own domain`() {
        assertTrue(
            customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, "https://cdn2.steamgriddb.com/hero/a.png"),
        )
        assertTrue(
            customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, "https://www.screenscraper.fr/image.php?x=1"),
        )
        // Cross-provider hosts are rejected: a mangled or hostile response must not redirect a download.
        assertFalse(
            customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, "https://www.screenscraper.fr/a.png"),
        )
        assertFalse(
            customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, "https://cdn2.steamgriddb.com/a.png"),
        )
    }

    @Test
    fun `plain http and lookalike domains are rejected`() {
        assertFalse(customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, "http://cdn2.steamgriddb.com/a.png"))
        // Suffix matching must be anchored on a dot, or "evilsteamgriddb.com" would pass.
        assertFalse(customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, "https://evilsteamgriddb.com/a.png"))
        assertFalse(customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, "https://notscreenscraper.fr/a.png"))
        assertFalse(
            customHeroUrlAllowed(
                CustomHeroSource.STEAMGRIDDB,
                "https://cdn2.steamgriddb.com:8443/a.png",
            ),
        )
        assertFalse(
            customHeroUrlAllowed(
                CustomHeroSource.SCREENSCRAPER,
                "https://user@www.screenscraper.fr/a.png",
            ),
        )
        assertFalse(
            customHeroUrlAllowed(
                CustomHeroSource.SCREENSCRAPER,
                "https://www.screenscraper.fr/a.png#credentials",
            ),
        )
        assertFalse(customHeroUrlAllowed(CustomHeroSource.STEAMGRIDDB, "not a url"))
    }

    @Test
    fun `redirects stay on the original provider and may be relative`() {
        assertEquals(
            "https://cdn2.steamgriddb.com/hero.png",
            customHeroRedirectTarget(
                CustomHeroSource.STEAMGRIDDB,
                "https://cdn2.steamgriddb.com/path/start",
                "/hero.png",
            ),
        )
        assertEquals(
            "https://www.screenscraper.fr/media/video.mp4?token=next",
            customHeroRedirectTarget(
                CustomHeroSource.SCREENSCRAPER,
                "https://www.screenscraper.fr/media/video.mp4?token=old",
                "?token=next",
            ),
        )
        assertEquals(
            null,
            customHeroRedirectTarget(
                CustomHeroSource.STEAMGRIDDB,
                "https://cdn2.steamgriddb.com/path/start",
                "https://example.com/credential-catcher",
            ),
        )
        assertEquals(
            null,
            customHeroRedirectTarget(
                CustomHeroSource.SCREENSCRAPER,
                "https://www.screenscraper.fr/image.php",
                "http://www.screenscraper.fr/insecure",
            ),
        )
        assertEquals(
            null,
            customHeroRedirectTarget(
                CustomHeroSource.SCREENSCRAPER,
                "https://www.screenscraper.fr/image.php",
                "https://www.screenscraper.fr:8443/image.php",
            ),
        )
    }

    @Test
    fun `provider json reader rejects oversized bodies instead of parsing a prefix`() {
        assertEquals(
            "{\"ok\":true}",
            readCustomHeroUtf8Bounded(ByteArrayInputStream("{\"ok\":true}".toByteArray()), 64),
        )
        assertEquals(
            null,
            readCustomHeroUtf8Bounded(ByteArrayInputStream("12345".toByteArray()), 4),
        )
    }

    @Test
    fun `video decode validation is structural and quality admission accepts verified 480p`() {
        assertTrue(validCustomHeroVideoMetadata(durationMillis = 1, width = 1920, height = 1080))
        assertTrue(validCustomHeroVideoMetadata(durationMillis = 1, width = 1280, height = 720))
        assertTrue(validCustomHeroVideoMetadata(durationMillis = 1, width = 720, height = 1280))
        assertTrue(validCustomHeroVideoMetadata(durationMillis = 1, width = 960, height = 540))
        assertFalse(validCustomHeroVideoMetadata(durationMillis = 1, width = 0, height = 1080))
        assertFalse(validCustomHeroVideoMetadata(durationMillis = 1, width = 1920, height = 0))
        assertFalse(validCustomHeroVideoMetadata(durationMillis = 0, width = 1920, height = 1080))

        assertTrue(validCustomHeroVideoApplyDimensions(width = 1920, height = 1080))
        assertTrue(validCustomHeroVideoApplyDimensions(width = 1280, height = 720))
        assertTrue(validCustomHeroVideoApplyDimensions(width = 720, height = 1280))
        assertTrue(validCustomHeroVideoApplyDimensions(width = 960, height = 540))
        assertTrue(validCustomHeroVideoApplyDimensions(width = 854, height = 480))
        assertFalse(validCustomHeroVideoApplyDimensions(width = 640, height = 360))
        assertFalse(validCustomHeroVideoApplyDimensions(width = 0, height = 1080))
    }

    @Test
    fun `static hero apply floor accepts official SteamGridDB canvases and rejects undersized banners`() {
        assertTrue(validCustomHeroStaticApplyDimensions(1_920, 1_080))
        assertTrue(validCustomHeroStaticApplyDimensions(1_280, 720))
        assertTrue(validCustomHeroStaticApplyDimensions(3_840, 1_240))
        assertTrue(validCustomHeroStaticApplyDimensions(1_920, 620))
        assertTrue(validCustomHeroStaticApplyDimensions(1_600, 650))
        assertFalse(validCustomHeroStaticApplyDimensions(720, 1_280))
        assertFalse(validCustomHeroStaticApplyDimensions(1_024, 620))
        assertFalse(validCustomHeroStaticApplyDimensions(1_280, 480))
    }

    @Test
    fun `display and apply predicates agree for every official SteamGridDB hero canvas`() {
        val officialCanvases = listOf(
            1_600 to 650,
            1_920 to 620,
            3_840 to 1_240,
        )
        officialCanvases.forEach { (width, height) ->
            assertTrue(
                "apply floor rejected $width x $height",
                validCustomHeroStaticApplyDimensions(width, height),
            )
            assertTrue(
                "filter rejected $width x $height",
                customEnrichHeroMatchesFilters(
                    CustomHeroCandidate(
                        source = CustomHeroSource.STEAMGRIDDB,
                        mediaType = CustomHeroMediaType.STATIC,
                        remoteUrl = "https://cdn2.steamgriddb.com/hero.png",
                        width = width,
                        height = height,
                    ),
                    size = "Any",
                    fit = "Any",
                ),
            )
        }
    }

    @Test
    fun `known SteamGridDB dimensions are filtered before the visible result cap`() {
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 1_920,
                height = 620,
                animated = false,
                size = "Any",
                fit = "Wide",
            ),
        )
        assertTrue(
            steamGridDbEntryKnownAndRejected(
                width = 1_600,
                height = 650,
                animated = false,
                size = "Any",
                fit = "Wide",
            ),
        )
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 1_600,
                height = 650,
                animated = false,
                size = "Any",
                fit = "Mimir",
            ),
        )
    }

    @Test
    fun `motion art accepts SteamGridDB native canvases and verified landscape 480p`() {
        assertTrue(validCustomHeroMotionArtDimensions(1_920, 620))
        assertTrue(validCustomHeroMotionArtDimensions(3_840, 1_240))
        assertTrue(validCustomHeroMotionArtDimensions(1_280, 480))
        assertTrue(validCustomHeroMotionArtDimensions(960, 540))
        assertTrue(validCustomHeroMotionArtDimensions(854, 480))
        assertFalse(validCustomHeroMotionArtDimensions(640, 360))
        assertFalse(validCustomHeroMotionArtDimensions(480, 854))
    }

    @Test
    fun `animated SteamGridDB candidates bypass static hero filtering but keep motion floor`() {
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 854,
                height = 480,
                animated = true,
                size = "Any",
                fit = "Any",
            ),
        )
        assertTrue(
            steamGridDbEntryKnownAndRejected(
                width = 640,
                height = 360,
                animated = true,
                size = "Any",
                fit = "Any",
            ),
        )
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 0,
                height = 0,
                animated = true,
                size = "Any",
                fit = "Any",
            ),
        )
    }

    @Test
    fun `steamGridDbEntryKnownAndRejected discriminates on size, not just fit`() {
        // R-23 exact-id passthrough: this is the exact per-entry filter steamGridDbHeroesInternal
        // applies with the caller's `size` (CustomHeroFetcher.kt line ~273). 1920x620 is an official
        // SteamGridDB canvas -- HD-eligible but below the 2K+ floor (`landscape && width >= 2560 &&
        // height >= 1000` in customEnrichHeroMatchesFilters) -- so it must flip from kept to rejected
        // as `size` moves from "Any" to "2K+", while a true 2K+ canvas stays kept.
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 1_920,
                height = 620,
                animated = false,
                size = "Any",
                fit = "Any",
            ),
        )
        assertTrue(
            steamGridDbEntryKnownAndRejected(
                width = 1_920,
                height = 620,
                animated = false,
                size = "2K+",
                fit = "Any",
            ),
        )
        assertFalse(
            steamGridDbEntryKnownAndRejected(
                width = 3_840,
                height = 1_240,
                animated = false,
                size = "2K+",
                fit = "Any",
            ),
        )
    }

    @Test
    fun `hero ordering prefers native targets and rejects unknown or below floor dimensions`() {
        assertEquals(0L, customHeroTargetDistance(1_920, 1_080))
        assertEquals(0L, customHeroTargetDistance(1_920, 620))
        assertTrue(customHeroTargetDistance(2_560, 1_440) < customHeroTargetDistance(3_840, 2_160))
        assertEquals(Long.MAX_VALUE, customHeroTargetDistance(1_024, 620))
        assertEquals(Long.MAX_VALUE, customHeroTargetDistance(0, 0))
    }

    @Test
    fun `ScreenScraper preview dimensions stay unverified for size and fit filtering`() {
        val unknown = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://www.screenscraper.fr/image.php?id=1",
        )

        val previewHydrated = customHeroWithHydratedDimensions(
            unknown,
            decodedWidth = 640,
            decodedHeight = 360,
            verified = false,
        )
        assertEquals(0, previewHydrated.width)
        assertEquals(0, previewHydrated.height)
        assertTrue(customEnrichHeroMatchesFilters(previewHydrated, size = "HD", fit = "Mimir"))

        val verified = customHeroWithHydratedDimensions(
            unknown,
            decodedWidth = 1_920,
            decodedHeight = 1_080,
            verified = true,
        )
        assertEquals(1_920, verified.width)
        assertEquals(1_080, verified.height)
        assertTrue(customEnrichHeroMatchesFilters(verified, size = "HD", fit = "Mimir"))
    }


    // ── Credential redaction ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the loggable label carries no url and therefore no credentials`() {
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.VIDEO,
            remoteUrl = "https://www.screenscraper.fr/image.php?devid=SECRET&sspassword=ALSOSECRET",
        )

        val label = customHeroSafeLogLabel(candidate)

        assertEquals("SCREENSCRAPER/VIDEO", label)
        assertFalse(label.contains("SECRET"))
        assertFalse(label.contains("devid"))
        assertFalse(label.contains("http"))
        val implicitDataClassString = candidate.toString()
        assertFalse(implicitDataClassString.contains("SECRET"))
        assertFalse(implicitDataClassString.contains("devid"))
        assertFalse(implicitDataClassString.contains("http"))
    }

    @Test
    fun `the user-facing label names provider and media type`() {
        assertEquals(
            "SteamGridDB · Image 1",
            CustomHeroCandidate(
                CustomHeroSource.STEAMGRIDDB,
                CustomHeroMediaType.STATIC,
                "https://cdn2.steamgriddb.com/a.png",
            ).displayLabel,
        )
        assertEquals(
            "ScreenScraper · Video 1",
            CustomHeroCandidate(
                CustomHeroSource.SCREENSCRAPER,
                CustomHeroMediaType.VIDEO,
                "https://www.screenscraper.fr/v.mp4",
            ).displayLabel,
        )
    }

    @Test
    fun `clip picker uses a still poster while the background player retains the video preview`() {
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.VIDEO,
            remoteUrl = "https://www.screenscraper.fr/v.mp4",
            previewPath = "/transient/p0.mp4",
            posterPath = "/transient/p0.jpg",
        )

        assertEquals("/transient/p0.jpg", candidate.pickerArtPath)
        assertEquals("/transient/p0.mp4", candidate.previewPath)
    }

    @Test
    fun `motion art uses its stable poster in the picker and keeps animation for playback`() {
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
            remoteUrl = "https://cdn2.steamgriddb.com/hero/motion.webp",
            previewPath = "/transient/motion.webp",
            posterPath = "/transient/motion.jpg",
        )

        assertEquals("/transient/motion.jpg", candidate.pickerArtPath)
        assertEquals("/transient/motion.webp", candidate.previewPath)
    }

    @Test
    fun `bounded static picker previews are never reused as installed hero bytes`() {
        assertFalse(customHeroPreviewReusableAsInstalledAsset(CustomHeroMediaType.STATIC))
        assertTrue(customHeroPreviewReusableAsInstalledAsset(CustomHeroMediaType.VIDEO))
        assertFalse(customHeroPreviewReusableAsInstalledAsset(CustomHeroMediaType.ANIMATED_IMAGE))
        assertFalse(validCustomHeroStaticApplyDimensions(640, 360))
        assertTrue(validCustomHeroStaticApplyDimensions(1_280, 720))
    }

    @Test
    fun `video path is never exposed as picker art when poster extraction is unavailable`() {
        val candidate = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.VIDEO,
            remoteUrl = "https://www.screenscraper.fr/v.mp4",
            previewPath = "/transient/p0.mp4",
        )

        assertEquals(null, candidate.pickerArtPath)
    }

    @Test
    fun `preview clip poster sample avoids the intro and stays inside short clips`() {
        assertEquals(400_000L, previewClipPosterTimeUs(400L))
        assertEquals(500_000L, previewClipPosterTimeUs(1_000L))
        assertEquals(1_500_000L, previewClipPosterTimeUs(10_000L))
        assertEquals(3_000_000L, previewClipPosterTimeUs(60_000L))
    }

    @Test
    fun `steamgrid ambiguity ranking is deterministic and interleaves bounded identities`() {
        val rows = listOf(
            10L to "Corpse Party",
            11L to "Corpse Party - Book of Shadows",
            12L to "Corpse Party Blood Drive",
            13L to "Unrelated Game",
        )
        val ranked = rankSteamGridDbAutocompleteMatches("Corpse Party", rows)
        assertEquals(listOf(10L), ranked.map { it.id })
        assertEquals(
            ranked,
            rankSteamGridDbAutocompleteMatches("Corpse Party", rows),
        )

        fun hero(id: Long, ordinal: Int) = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://cdn2.steamgriddb.com/$id-$ordinal.png",
            providerAssetId = id.toString() + "-$ordinal",
            ordinal = ordinal,
            resolvedTitle = rows.first { it.first == id }.second,
        )
        val interleaved = roundRobinDistinctCustomHeroCandidates(
            groups = listOf(
                listOf(hero(10L, 1), hero(10L, 2)),
                listOf(hero(12L, 1)),
            ),
            limit = 3,
        )
        assertEquals(listOf("10-1", "12-1", "10-2"), interleaved.map { it.providerAssetId })
        assertEquals(
            listOf("Corpse Party", "Corpse Party Blood Drive", "Corpse Party"),
            interleaved.map { it.resolvedTitle },
        )
    }

    @Test
    fun `steamgrid autocomplete ranking table covers identity normalization and fanout`() {
        data class Case(
            val query: String,
            val rows: List<Pair<Long, String>>,
            val expectedIds: List<Long>,
        )
        val cases = listOf(
            Case(
                query = "DMC3",
                rows = listOf(
                    1L to "Devil May Cry 3",
                    2L to "Devil May Cry 2",
                    3L to "Unrelated Game",
                ),
                expectedIds = listOf(1L),
            ),
            Case(
                query = "Pokemon Ruby",
                rows = listOf(
                    10L to "Pokémon Ruby",
                    11L to "Pokemon Sapphire",
                    12L to "Unrelated Game",
                ),
                expectedIds = listOf(10L),
            ),
            Case(
                query = "Final Fantasy 3",
                rows = listOf(
                    20L to "Final Fantasy III",
                    21L to "Final Fantasy IV",
                    22L to "Unrelated Game",
                ),
                expectedIds = listOf(20L),
            ),
            Case(
                query = "Legend of Zelda, The - The Minish Cap",
                rows = listOf(
                    30L to "The Legend of Zelda: The Minish Cap",
                    31L to "The Legend of Zelda: Phantom Hourglass",
                ),
                expectedIds = listOf(30L),
            ),
            Case(
                query = "Metroid",
                rows = listOf(
                    40L to "Metroid Prime",
                    41L to "Metroid Fusion",
                    42L to "Metroid: Zero Mission",
                    43L to "Unrelated Game",
                ),
                expectedIds = listOf(41L, 40L, 42L),
            ),
            Case(
                query = "Obscure Query",
                rows = listOf(
                    50L to "Alpha Game",
                    51L to "Beta Game",
                    52L to "Gamma Game",
                    53L to "Delta Game",
                ),
                expectedIds = listOf(50L, 51L, 52L),
            ),
        )
        cases.forEach { case ->
            val ranked = rankSteamGridDbAutocompleteMatches(case.query, case.rows)
            assertEquals(
                "query=${case.query}",
                case.expectedIds,
                ranked.map { it.id },
            )
            assertEquals(
                "query=${case.query} stable repeat",
                ranked,
                rankSteamGridDbAutocompleteMatches(case.query, case.rows),
            )
        }
        assertFalse(
            rankSteamGridDbAutocompleteMatches(
                "Final Fantasy III",
                listOf(60L to "Final Fantasy IV"),
            ).any { it.id == 60L },
        )
        assertTrue(steamGridDbTitleMatchRank("Corpse Party", "Corpse Party - Book of Shadows") != null)
    }

    // ── ScreenScraper media filtering ────────────────────────────────────────────────────────────

    /**
     * R5-3, policy 2026-08-25. The Cover picker's shelf, in order, each candidate self-labelling.
     *
     * `box-3d` sitting second is the whole reason this picker had to exist: every title the provider
     * carries also publishes `box-2D`, and the automatic ladder is `box-2D → box-3D → box-texture`, so a
     * 3D box is unreachable automatically in the reference corpus. Ranking it second here is what makes it
     * the first thing he sees after the automatic winner.
     */
    @Test
    fun `cover candidates follow the shelf priority and name themselves`() {
        val provider = listOf(
            "box-scan" to "https://www.screenscraper.fr/scan.png",
            "box-2D-side" to "https://www.screenscraper.fr/side.png",
            "box-texture" to "https://www.screenscraper.fr/tex.png",
            "box-2D-back" to "https://www.screenscraper.fr/back.png",
            "box-3D" to "https://www.screenscraper.fr/b3.png",
            "box-2D" to "https://www.screenscraper.fr/b2.png",
            "support-2D" to "https://www.screenscraper.fr/disc.png",
        )
        val candidates = screenScraperCoverCandidates(provider)

        assertEquals(
            // 3D leads, and the physical article follows the boxes since
            // N-3 -- this picker became the only route to EITHER vocabulary when the two media rows became
            // two state rows, so withholding discs would have removed his ability to choose one.
            "ranked by candidate priority, NOT by provider response order",
            listOf("box-3d", "box-2d", "box-texture", "box-2d-back", "box-scan", "support-2d"),
            candidates.map { it.mediaSubtype },
        )
        // The spine is still excluded, on geometry.
        assertTrue(candidates.none { it.mediaSubtype == "box-2d-side" })

        // Every one names what it is. An unlabelled candidate is the silent substitution the dup-cover
        // ban exists to prevent -- a picker is legitimate precisely because he SEES what he is choosing.
        assertEquals(
            listOf(
                "ScreenScraper · Box (3D) 1",
                "ScreenScraper · Box (2D) 2",
                "ScreenScraper · Box texture 3",
                "ScreenScraper · Box (back) 4",
                "ScreenScraper · Box scan 5",
                "ScreenScraper · Disc / cart 6",
            ),
            candidates.map { it.displayLabel },
        )
    }

    /**
     * Caught on device, 2026-08-25: the cover picker captioned real box art **"Physical media"**.
     *
     * Cover candidates were being mapped through [asPhysicalIconCandidate], which labels the SUPPORT
     * vocabulary and falls through to "Physical media" for anything it does not recognise — which is every
     * box type. It was wrong, and it was long enough to wrap mid-word in the portrait tile, so the screen
     * read "ScreenS craper ·" with the part that carries the meaning cut off entirely.
     *
     * The caption is now the type ALONE. R5-2 asks that a candidate be labelled for what it IS, and on a
     * tile this narrow the provider prefix costs exactly that.
     */
    @Test
    fun `a cover candidate is captioned by its box type, not as physical media`() {
        val covers = screenScraperCoverCandidates(
            listOf(
                "box-2D" to "https://www.screenscraper.fr/b2.png",
                "box-3D" to "https://www.screenscraper.fr/b3.png",
                "box-scan" to "https://www.screenscraper.fr/scan.png",
                "support-2D" to "https://www.screenscraper.fr/disc.png",
            ),
        )
        assertEquals(
            // A disc offered in the face picker must name itself as a disc. A candidate is legitimate
            // because he SEES what it is -- so it may never arrive wearing a box's name.
            listOf("Box (3D)", "Box (2D)", "Box scan", "Disc / cart"),
            covers.map { it.asCoverCandidate().providerLabel },
        )
        covers.forEach {
            assertFalse(
                "the support vocabulary must never caption a cover",
                it.asCoverCandidate().providerLabel.contains("Physical"),
            )
            assertFalse(
                "a cover is portrait -- squaring it would letterbox what this picker exists to judge",
                it.asCoverCandidate().normalizeSquare,
            )
        }
    }

    private val medias = listOf(
        "box-2D" to "https://www.screenscraper.fr/box.png",
        "ss" to "https://www.screenscraper.fr/shot.png",
        "video" to "https://www.screenscraper.fr/clip.mp4",
        "fanart" to "https://www.screenscraper.fr/fan.png",
        "manuel" to "https://www.screenscraper.fr/manual.pdf",
    )

    @Test
    fun `hero types are ordered fanart then screenshots then video, with box art last and non-media excluded`() {
        val candidates = screenScraperHeroCandidates(medias, videoAllowed = true)

        assertEquals(
            listOf(
                "https://www.screenscraper.fr/fan.png",
                "https://www.screenscraper.fr/shot.png",
                "https://www.screenscraper.fr/clip.mp4",
                // box art is now offered, and it is offered LAST. Every real
                // backdrop outranks it, so it is reached only by a title that has none.
                "https://www.screenscraper.fr/box.png",
            ),
            candidates.map { it.remoteUrl },
        )
        // `manuel` is not presentation media at all and never becomes a candidate.
        assertFalse(candidates.any { it.remoteUrl.contains("manual") })
    }

    /**
     * The explicit Hero picker admits box textures and 3D/2D covers as labelled choices.
     *
     * This test replaces one that pinned the opposite (`box-texture stays excluded from Hero
     * candidates`). That pin was correct for the 2026-08-12 ruling, which scoped box-texture to the
     * physical-media picker; it is corrected here rather than left asserting an overturned claim.
     *
     * The dup-cover ban is NOT retired, and the assertions say where it still lives: this list feeds the
     * explicit picker, every candidate carries an honest self-naming label, and
     * `ArtMediaResolver.HERO_TYPE_PRIORITY` -- the AUTOMATIC enrichment ranking -- is untouched and still
     * carries only fanart/sstitle/ss (pinned in core-metadata, which owns that constant).
     */
    @Test
    fun `box art is offered in the Hero picker, ranked last, and labels itself honestly`() {
        val candidates = screenScraperHeroCandidates(
            medias + ("box-texture" to "https://www.screenscraper.fr/boxtex.png"),
            videoAllowed = true,
        )

        assertEquals(5, candidates.size)
        // box-texture leads the box family (the flat wrapper is the only backdrop-shaped one of the three)
        // but the whole family still sits behind every real backdrop type.
        assertEquals(
            listOf(
                "https://www.screenscraper.fr/fan.png",
                "https://www.screenscraper.fr/shot.png",
                "https://www.screenscraper.fr/clip.mp4",
                "https://www.screenscraper.fr/boxtex.png",
                "https://www.screenscraper.fr/box.png",
            ),
            candidates.map { it.remoteUrl },
        )
        val boxTexture = candidates.single { it.remoteUrl.contains("boxtex") }
        val box2d = candidates.single { it.remoteUrl.endsWith("box.png") }
        // The label is the ruling: an unlabelled box candidate is the silent substitution the ban bars.
        assertEquals("ScreenScraper · Box texture 4", boxTexture.displayLabel)
        assertEquals("ScreenScraper · Box (2D) 5", box2d.displayLabel)
        // And the subtype is the provider's real type -- it used to report "fanart" for every still.
        assertEquals("box-texture", boxTexture.mediaSubtype)
        assertEquals("box-2d", box2d.mediaSubtype)
        assertEquals("fanart", candidates.first().mediaSubtype)
        assertEquals("ss", candidates[1].mediaSubtype)
    }

    @Test
    fun `video candidates are withheld unless the separate preview-video opt-in allows them`() {
        val candidates = screenScraperHeroCandidates(medias, videoAllowed = false)

        assertTrue(candidates.none { it.mediaType == CustomHeroMediaType.VIDEO })
        // fanart + ss + the box-2D admitted by the 2026-08-23 ruling.
        assertEquals(3, candidates.size)
    }

    @Test
    fun `non-https media never become candidates`() {
        val candidates = screenScraperHeroCandidates(
            listOf("fanart" to "http://www.screenscraper.fr/insecure.png"),
            videoAllowed = true,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `an https lookalike host never becomes a ScreenScraper candidate`() {
        val candidates = screenScraperHeroCandidates(
            listOf("fanart" to "https://evil-screenscraper.fr/credential-catcher.png"),
            videoAllowed = true,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `ScreenScraper stills use their bounded media url as preview while videos stay labelled`() {
        val candidates = screenScraperHeroCandidates(medias, videoAllowed = true)

        assertEquals(candidates[0].remoteUrl, candidates[0].thumbUrl)
        assertEquals(candidates[1].remoteUrl, candidates[1].thumbUrl)
        assertEquals(null, candidates[2].thumbUrl)
        // The box candidate is a still like any other still: it previews from its own bounded media url.
        assertEquals(candidates[3].remoteUrl, candidates[3].thumbUrl)
        assertEquals(listOf(1, 2, 3, 4), candidates.map { it.ordinal })
    }

    @Test
    fun `clip picker prefers original video before normalized encode`() {
        val mediaRich = buildList {
            repeat(20) { add("ss" to "https://www.screenscraper.fr/shot-$it.png") }
            add("video-normalized" to "https://www.screenscraper.fr/clip-small.mp4")
            add("video" to "https://www.screenscraper.fr/clip.mp4")
        }

        assertEquals(
            listOf(
                "https://www.screenscraper.fr/clip.mp4",
                "https://www.screenscraper.fr/clip-small.mp4",
            ),
            screenScraperPreviewClipCandidates(mediaRich).map { it.remoteUrl },
        )
        assertTrue(screenScraperPreviewClipCandidates(mediaRich).all {
            it.thumbUrl == "https://www.screenscraper.fr/shot-0.png"
        })
    }

    @Test
    fun `Hero Motion format filter exposes both videos under All and fails closed otherwise`() {
        val original = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.VIDEO,
            remoteUrl = "https://www.screenscraper.fr/clip.mp4",
            mediaSubtype = "video",
        )
        val normalized = original.copy(
            remoteUrl = "https://www.screenscraper.fr/clip-small.mp4",
            mediaSubtype = "video-normalized",
        )

        assertTrue(screenScraperHeroMotionMatchesFormat(original, "All"))
        assertTrue(screenScraperHeroMotionMatchesFormat(normalized, "All"))
        assertTrue(screenScraperHeroMotionMatchesFormat(original, "Video"))
        assertTrue(screenScraperHeroMotionMatchesFormat(normalized, "Video"))
        assertFalse(screenScraperHeroMotionMatchesFormat(original, "Motion Art"))
        assertFalse(screenScraperHeroMotionMatchesFormat(original, "Original"))
        assertFalse(screenScraperHeroMotionMatchesFormat(normalized, "Normalized"))
        assertFalse(screenScraperHeroMotionMatchesFormat(original, "unexpected"))
    }

    @Test
    fun `physical picker admits only support media (plus the box-texture fallback) in cartridge first order`() {
        val mediaRich = buildList {
            repeat(20) { add("ss" to "https://www.screenscraper.fr/shot-$it.png") }
            add("support-label" to "https://www.screenscraper.fr/label.png")
            add("support-3d" to "https://www.screenscraper.fr/case.png")
            add("support-2d" to "https://www.screenscraper.fr/front.png")
            add("support-texture" to "https://www.screenscraper.fr/texture.png")
        }

        val candidates = screenScraperPhysicalMediaCandidates(mediaRich)

        assertEquals(
            listOf("support-2d", "support-3d", "support-label", "support-texture"),
            candidates.map { it.mediaSubtype },
        )
        val icon = candidates.first().copy(previewPath = "/tmp/front.png").asPhysicalIconCandidate()
        assertTrue(icon.normalizeSquare)
        assertEquals(EnrichSavedSource.SCREENSCRAPER, icon.savedSource)
        assertEquals("ScreenScraper · Physical front", icon.providerLabel)
    }

    //  Ground truth: ScreenScraper returned 21 media types and zero support-* entries for Resident
    // Evil 5 (Xbox 360) in Customize Media, but did carry box-texture -- the empty picker was correct, and
    // box-texture fills that gap. The two tests below pin both directions: a genuine
    // support-* scan must always outrank the fallback, and a title with only box-texture must resolve to
    // it instead of an empty picker.
    @Test
    fun `a genuine support scan always outranks the box-texture fallback in the physical picker`() {
        val mediaRich = listOf(
            // box-texture appears FIRST in provider order; the ranked type priority must still win.
            "box-texture" to "https://www.screenscraper.fr/boxtex.png",
            "support-2d" to "https://www.screenscraper.fr/front.png",
        )

        val candidates = screenScraperPhysicalMediaCandidates(mediaRich)

        assertEquals(listOf("support-2d", "box-texture"), candidates.map { it.mediaSubtype })
        assertEquals("https://www.screenscraper.fr/front.png", candidates.first().remoteUrl)
    }

    @Test
    fun `a title with only box-texture resolves to box-texture instead of an empty physical picker`() {
        val boxTextureOnly = listOf("box-texture" to "https://www.screenscraper.fr/boxtex.png")

        val candidates = screenScraperPhysicalMediaCandidates(boxTextureOnly)

        assertEquals(listOf("box-texture"), candidates.map { it.mediaSubtype })
        val icon = candidates.first().copy(previewPath = "/tmp/boxtex.png").asPhysicalIconCandidate()
        assertEquals("ScreenScraper · Box texture (fallback)", icon.providerLabel)
    }

    // ── Merge + availability ─────────────────────────────────────────────────────────────────────

    private fun sgdb(n: Int) = CustomHeroCandidate(
        CustomHeroSource.STEAMGRIDDB,
        CustomHeroMediaType.STATIC,
        "https://cdn2.steamgriddb.com/$n.png",
    )

    @Test
    fun `merge keeps SteamGridDB first, dedupes, and stays bounded`() {
        val ss = screenScraperHeroCandidates(medias, videoAllowed = true)

        val merged = mergeCustomHeroCandidates(listOf(sgdb(1), sgdb(1), sgdb(2)), ss)

        assertEquals(CustomHeroSource.STEAMGRIDDB, merged.first().source)
        assertEquals("duplicates collapse", 2, merged.count { it.source == CustomHeroSource.STEAMGRIDDB })
        assertTrue(merged.size <= CustomHeroMaxCandidates)
    }

    @Test
    fun `merge shows whichever single provider succeeded`() {
        assertEquals(1, mergeCustomHeroCandidates(listOf(sgdb(1)), emptyList()).size)
        assertEquals(
            4,
            mergeCustomHeroCandidates(emptyList(), screenScraperHeroCandidates(medias, videoAllowed = true)).size,
        )
    }

    @Test
    fun `the unavailable reason names exactly what is missing`() {
        assertEquals(
            CustomHeroUnavailableReason.NEEDS_BOTH,
            customHeroUnavailableReason(hasSteamGridDbKey = false, hasScreenScraperAccount = false),
        )
        assertEquals(
            CustomHeroUnavailableReason.NEEDS_STEAMGRIDDB_KEY,
            customHeroUnavailableReason(hasSteamGridDbKey = false, hasScreenScraperAccount = true),
        )
        assertEquals(
            CustomHeroUnavailableReason.NEEDS_SCREENSCRAPER_ACCOUNT,
            customHeroUnavailableReason(hasSteamGridDbKey = true, hasScreenScraperAccount = false),
        )
    }

    @Test
    fun `provider failures stay distinct from unreachable and verified empty`() {
        val empty = CustomHeroProviderResult.Loaded(emptyList())
        assertEquals(
            "Couldn’t reach artwork services. Saved choices are still available.",
            customHeroProviderFailureNote(
                CustomHeroProviderResult.Unreachable,
                CustomHeroProviderResult.Unreachable,
            ),
        )
        assertEquals(
            "Artwork services couldn’t complete this request. Saved choices are still available.",
            customHeroProviderFailureNote(
                CustomHeroProviderResult.ProviderFailed,
                CustomHeroProviderResult.ProviderFailed,
            ),
        )
        assertEquals(
            "Couldn’t reach SteamGridDB.",
            customHeroProviderFailureNote(CustomHeroProviderResult.Unreachable, empty),
        )
        assertEquals(
            "ScreenScraper couldn’t complete this request.",
            customHeroProviderFailureNote(empty, CustomHeroProviderResult.ProviderFailed),
        )
        assertEquals(null, customHeroProviderFailureNote(empty, empty))
    }

    @Test
    fun `needs access maps to the existing steamgrid key copy`() {
        assertEquals(
            CustomHeroUnavailableReason.NEEDS_STEAMGRIDDB_KEY.message,
            customHeroProviderFailureNote(CustomHeroProviderResult.NeedsAccess, null),
        )
    }

    @Test
    fun `upload and legacy saved sources never coerce to a remote hero provider`() {
        assertNull(EnrichSavedSource.UPLOAD.toCustomHeroSource())
        assertNull(EnrichSavedSource.LEGACY.toCustomHeroSource())
        assertEquals(CustomHeroSource.STEAMGRIDDB, EnrichSavedSource.STEAMGRIDDB.toCustomHeroSource())
        assertEquals(CustomHeroSource.SCREENSCRAPER, EnrichSavedSource.SCREENSCRAPER.toCustomHeroSource())
    }

    @Test
    fun `screen scraper platform candidates carry explicit provider attribution`() {
        val candidate = SteamGridDbTileFetcher.Candidate(
            url = "https://www.screenscraper.fr/image.php?x=1",
            thumbUrl = "https://www.screenscraper.fr/image.php?x=1",
            providerLabel = "ScreenScraper",
            savedSource = EnrichSavedSource.SCREENSCRAPER,
        )
        assertEquals("ScreenScraper", candidate.providerLabel)
        assertEquals(EnrichSavedSource.SCREENSCRAPER, candidate.savedSource)
    }

    @Test
    fun `source and route mismatch performs zero opens`() {
        var opens = 0
        val recorder = MimirProviderEgressOpener { _, _, _, _ ->
            opens++
            null
        }
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(true)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
                "https://cdn2.steamgriddb.com/hero/a.png",
                8_000,
                15_000,
                opener = recorder,
            ),
        )
        assertEquals(0, opens)
    }

    @Test
    fun `closed steamgrid gate blocks media egress for hero downloads`() {
        var opens = 0
        val recorder = MimirProviderEgressOpener { _, _, _, _ ->
            opens++
            null
        }
        MimirSteamGridDbGate.setOpen(false)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
                "https://cdn2.steamgriddb.com/hero/a.png",
                8_000,
                15_000,
                opener = recorder,
            ),
        )
        assertEquals(0, opens)
    }

    @Test
    fun `screen scraper media never opens when only steamgrid gate is open`() {
        var opens = 0
        val recorder = MimirProviderEgressOpener { _, _, _, _ ->
            opens++
            null
        }
        MimirSteamGridDbGate.setOpen(true)
        MimirConnectedArtGate.setOpen(false)
        assertNull(
            MimirProviderEgress.open(
                MimirProviderEgressRoute.SCREENSCRAPER_MEDIA,
                "https://www.screenscraper.fr/image.php?x=1",
                8_000,
                15_000,
                opener = recorder,
            ),
        )
        assertEquals(0, opens)
    }

    @Test
    fun `clip result mapping uses picker art not raw video path`() {
        val result = HomeCustomEnrichResult.Clip(
            candidate = CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.VIDEO,
                remoteUrl = "https://www.screenscraper.fr/v.mp4",
                previewPath = "/transient/p0.mp4",
                posterPath = "/transient/p0.jpg",
            ),
        )
        assertEquals("/transient/p0.jpg", result.previewPath)
    }

    @Test
    fun `motion result can fall back to its safe animated preview when no poster was derived`() {
        val result = HomeCustomEnrichResult.Clip(
            candidate = CustomHeroCandidate(
                source = CustomHeroSource.STEAMGRIDDB,
                mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
                remoteUrl = "https://cdn2.steamgriddb.com/hero/motion.webp",
                previewPath = "/transient/motion.webp",
            ),
        )
        assertEquals("/transient/motion.webp", result.previewPath)
    }

    @Test
    fun `physical ScreenScraper previews may download up to the asset bound before downsampling`() {
        val physical = CustomHeroCandidate(
            source = CustomHeroSource.SCREENSCRAPER,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = "https://www.screenscraper.fr/front.png",
            mediaSubtype = "support-2d",
        )
        val thumb = physical.copy(mediaSubtype = null)
        assertEquals(CustomHeroStore.MaxAssetBytes, CustomHeroFetcher.staticPreviewSourceByteBound(physical))
        assertEquals(2L * 1024L * 1024L, CustomHeroFetcher.staticPreviewSourceByteBound(thumb))
    }

    @Test
    fun `animated provider thumbnails may download up to the static asset bound before downsampling`() {
        val motion = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
            remoteUrl = "https://cdn2.steamgriddb.com/hero/motion.webp",
            thumbUrl = "https://cdn2.steamgriddb.com/hero/motion-thumb.webp",
        )
        assertEquals(CustomHeroStore.MaxAssetBytes, CustomHeroFetcher.staticPreviewSourceByteBound(motion))
    }

    @Test
    fun `animated saved media cannot be reused as an oversized picker preview`() {
        val root = kotlin.io.path.createTempDirectory("motion-preview-bound").toFile()
        try {
            val oversized = File(root, "motion.webp")
            java.io.RandomAccessFile(oversized, "rw").use {
                it.setLength(CustomHeroStore.MaxPreviewBytes + 1)
            }
            val motion = CustomHeroCandidate(
                source = CustomHeroSource.STEAMGRIDDB,
                mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
                remoteUrl = "https://cdn2.steamgriddb.com/hero/motion.webp",
            )

            assertNull(customHeroReusableCachedPreview(motion, oversized.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `motion picker falls back to the bounded original after its provider thumbnail`() {
        val thumb = "https://cdn2.steamgriddb.com/hero/motion-thumb.webp"
        val original = "https://cdn2.steamgriddb.com/hero/motion.webp"
        val motion = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
            remoteUrl = original,
            thumbUrl = thumb,
        )

        assertEquals(listOf(thumb, original), customHeroPickerPreviewUrls(motion))
        assertEquals(
            listOf(original),
            customHeroPickerPreviewUrls(motion.copy(thumbUrl = null)),
        )
    }

    @Test
    fun `static picker never downloads its original as a thumbnail fallback`() {
        val original = "https://cdn2.steamgriddb.com/hero/static.png"
        val still = CustomHeroCandidate(
            source = CustomHeroSource.STEAMGRIDDB,
            mediaType = CustomHeroMediaType.STATIC,
            remoteUrl = original,
            thumbUrl = null,
        )

        assertTrue(customHeroPickerPreviewUrls(still).isEmpty())
        assertEquals(
            listOf(original),
            customHeroPickerPreviewUrls(still.copy(thumbUrl = original)),
        )
    }

    @Test
    fun `custom hero byte ceilings distinguish static heroes from Hero Motion originals`() {
        assertEquals(CustomHeroStore.MaxAssetBytes, customHeroMaxAssetBytes(CustomHeroMediaType.STATIC))
        assertEquals(CustomHeroStore.MaxMotionAssetBytes, customHeroMaxAssetBytes(CustomHeroMediaType.VIDEO))
        assertEquals(CustomHeroStore.MaxMotionAssetBytes, customHeroMaxAssetBytes(CustomHeroMediaType.ANIMATED_IMAGE))
    }

    @Test
    fun `reusable candidate poster accepts only bounded hydrated picker art`() {
        val dir = kotlin.io.path.createTempDirectory("mimir-poster-fallback").toFile()
        try {
            val valid = File(dir, "poster.jpg").apply { writeBytes(ByteArray(1024)) }
            val oversized = File(dir, "large.jpg").apply {
                writeBytes(ByteArray((CustomHeroStore.MaxPreviewBytes + 1).toInt()))
            }
            val candidate = CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.VIDEO,
                remoteUrl = "https://www.screenscraper.fr/clip.mp4",
                previewPath = "/cache/clip.mp4",
                posterPath = valid.absolutePath,
            )
            assertEquals(valid, CustomHeroFetcher.customHeroReusableCandidatePoster(candidate))
            assertNull(
                CustomHeroFetcher.customHeroReusableCandidatePoster(
                    candidate.copy(posterPath = oversized.absolutePath),
                ),
            )
            assertNull(
                CustomHeroFetcher.customHeroReusableCandidatePoster(
                    candidate.copy(posterPath = candidate.previewPath),
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── R-23/R-25 PC-games Steam app id  ───────────────

    @Test
    fun `a validated steam shortcut payload yields its app id`() {
        val file = tempShortcutFile("Blasphemous 2.steam", "2114740")
        val result = GameNativeLaunchRoute.parse(file)
        assertEquals(2114740, steamAppIdFromShortcutParse(result))
    }

    @Test
    fun `the other four GameNative extensions parse but are not treated as an app id source`() {
        // R-25: real, validly-parsed payloads for every one of GameNative's other sources -- these are
        // not malformed inputs, they are just the four extensions that identify by filename instead.
        listOf(
            "Hades.epic" to "EPIC",
            "Disco Elysium.gog" to "GOG",
            "Cyberpunk 2077.amazon" to "AMAZON",
            "My Ripped Game.pcgame" to "CUSTOM_GAME",
        ).forEach { (name, expectedSource) ->
            val file = tempShortcutFile(name, "12345")
            val result = GameNativeLaunchRoute.parse(file)
            assertEquals(
                "$name should still parse to a payload",
                GameNativeLaunchRoute.ParseResult.Payload(12345, expectedSource),
                result,
            )
            assertNull("$name must not be read as a Steam app id", steamAppIdFromShortcutParse(result))
        }
    }

    @Test
    fun `an unreadable or malformed shortcut yields no app id`() {
        val missing = File(Files.createTempDirectory("pc-games-appid-test").toFile(), "Ghost.steam")
        assertNull(steamAppIdFromShortcutParse(GameNativeLaunchRoute.parse(missing)))

        val malformed = tempShortcutFile("Bad.steam", "not-a-number")
        assertNull(steamAppIdFromShortcutParse(GameNativeLaunchRoute.parse(malformed)))
    }

    // ── steamAppIdForManagedPath (policy 2026-08-19, ENRICH-for-pc_games): the one seam both
    // pcGameSteamAppId (has a GameRow, needs a DAO hop for the managed path) and
    // ArtworkFetchController's Enrich route (already holds a GameEntity with managedPath in hand) resolve
    // an app id through. Pure/JVM: exercises the exact File the DAO-backed caller would have produced,
    // without needing Room or a Context.

    @Test
    fun `steamAppIdForManagedPath resolves a valid steam shortcut`() {
        val file = tempShortcutFile("Blasphemous 2.steam", "2114740")
        assertEquals(2114740, steamAppIdForManagedPath(file.absolutePath))
    }

    @Test
    fun `steamAppIdForManagedPath is null for a null or blank managed path`() {
        // A SAF-only pc_games entry has no managedPath at all -- must not crash or invent a fallback.
        assertNull(steamAppIdForManagedPath(null))
        assertNull(steamAppIdForManagedPath(""))
        assertNull(steamAppIdForManagedPath("   "))
    }

    @Test
    fun `steamAppIdForManagedPath refuses the other four GameNative extensions R-25`() {
        // Same R-25 refusal as steamAppIdFromShortcutParse, reachable through the managed-path seam too.
        listOf("Hades.epic", "Disco Elysium.gog", "Cyberpunk 2077.amazon", "My Ripped Game.pcgame")
            .forEach { name ->
                val file = tempShortcutFile(name, "12345")
                assertNull(
                    "$name must not be read as a Steam app id via the managed-path seam",
                    steamAppIdForManagedPath(file.absolutePath),
                )
            }
    }

    @Test
    fun `steamAppIdForManagedPath is null for an unreadable or malformed shortcut`() {
        val missing = File(Files.createTempDirectory("pc-games-appid-test").toFile(), "Ghost.steam")
        assertNull(steamAppIdForManagedPath(missing.absolutePath))

        val malformed = tempShortcutFile("Bad.steam", "not-a-number")
        assertNull(steamAppIdForManagedPath(malformed.absolutePath))
    }

    // ── R-23 exact-id Size/Fit passthrough  ──────────────────────────────
    //
    // steamGridDbHeroesInternal's own query building (heroDimensionsQuery) and per-entry filtering
    // (steamGridDbEntryKnownAndRejected, pinned above) only run after a live SteamGridDB fetch, and
    // getJsonOrThrow has no injectable opener seam the way validateSteamGridDbKey does -- so the
    // emitted `&dimensions=` query string cannot be observed here without either a real network call
    // (disallowed) or threading a new opener parameter through getJsonOrThrow (out of scope for this
    // change). What IS observable network-free: both call shapes below still compile and behave
    // identically along the short-circuit paths that never touch size/fit, confirming the widened
    // signature does not disturb either caller.

    @Test
    fun `steamGridDbHeroesForSteamAppId legacy two-arg call still resolves the tile-menu shape`() = runBlocking {
        // Matches CustomHeroStore.kt:887 exactly: two positional args, no Size/Fit at all. Must keep
        // compiling and behaving unchanged now that size/fit are real parameters instead of hardcoded.
        val result = CustomHeroFetcher.steamGridDbHeroesForSteamAppId("", 12345)
        assertEquals(CustomHeroProviderResult.Loaded(emptyList()), result)
    }

    @Test
    fun `steamGridDbHeroesForSteamAppId accepts the picker's named Size and Fit`() = runBlocking {
        // Matches MimirAppCoordinator's new call shape: named, non-default size/fit. Both this and the
        // omitted-args call reach NeedsAccess identically here because the gate check runs before
        // size/fit are ever used (see the note above) -- this pins that the named-argument call compiles
        // and is accepted, not the literal forwarded value.
        MimirSteamGridDbGate.setOpen(false)
        val omitted = CustomHeroFetcher.steamGridDbHeroesForSteamAppId("real-key", 12345)
        val named = CustomHeroFetcher.steamGridDbHeroesForSteamAppId(
            "real-key",
            12345,
            size = "2K+",
            fit = "Wide",
        )
        assertEquals(CustomHeroProviderResult.NeedsAccess, omitted)
        assertEquals(CustomHeroProviderResult.NeedsAccess, named)
    }

    @Test
    fun `steamGridDbHeroesForGame short-circuits identically for both resolved-game shapes`() = runBlocking {
        // the Library's ENRICH hero step is keyed by whichever SteamGridDbGameRef
        // the one identification step produced. Both shapes take the same network-free short-circuits the
        // legacy app-id entry point takes (blank key -> Loaded(empty), closed gate -> NeedsAccess); the
        // legacy entry point is now a thin wrapper over this one, so the two cannot drift.
        assertEquals(
            CustomHeroProviderResult.Loaded(emptyList()),
            CustomHeroFetcher.steamGridDbHeroesForGame("", SteamGridDbGameRef.Game(5L)),
        )
        assertEquals(
            CustomHeroProviderResult.Loaded(emptyList()),
            CustomHeroFetcher.steamGridDbHeroesForGame("", SteamGridDbGameRef.SteamApp(12345)),
        )
        MimirSteamGridDbGate.setOpen(false)
        assertEquals(
            CustomHeroProviderResult.NeedsAccess,
            CustomHeroFetcher.steamGridDbHeroesForGame("real-key", SteamGridDbGameRef.Game(5L)),
        )
    }

    private fun tempShortcutFile(name: String, body: String): File {
        val dir = Files.createTempDirectory("pc-games-appid-test").toFile()
        return File(dir, name).apply { writeText(body) }
    }
}
