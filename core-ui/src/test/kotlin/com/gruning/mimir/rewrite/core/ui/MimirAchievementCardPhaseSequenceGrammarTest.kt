package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MimirAchievementCard]'s phase-varying body (2026-08-19): a phase change across
 * [MimirAchievementCardState]'s three pages is ONE surface advancing (fade + resize on one clock, page
 * identity via `contentKey`), not a fresh host-level crossfade -- `MimirAppHost.kt`'s GAMELIST_ACHIEVEMENTS
 * phase-map arm was deleted in the SAME commit (see `app`'s `ArtworkPhaseSequenceGrammarTest.kt`, which
 * cannot reach this `core-ui` module's own source).
 *
 * Same two-class split as `app`'s `ArtworkPhaseSequenceGrammarTest.kt`, for the same reason:
 * [MimirAchievementCardPageKeyTest] exercises the pure, Compose-free page-key function directly;
 * [MimirAchievementCardSourceTest] pins the `AnimatedContent` wiring as source text because its
 * `transitionSpec`/content-lambda body runs on a Compose animation clock a plain JVM test cannot sample.
 */
class MimirAchievementCardPageKeyTest {
    @Test
    fun `all three MimirAchievementCardState subclasses are distinct page keys`() {
        val keys = listOf(
            mimirAchievementCardPageKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking)),
            mimirAchievementCardPageKey(MimirAchievementCardState.NotMatched("no set")),
            mimirAchievementCardPageKey(sampleLoaded()),
        )
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `Unknown's page key does not discriminate on tone or reason -- this is the deliberate seam`() {
        // "Checking..." (PENDING) -> "couldn't reach
        // RetroAchievements" (UNAVAILABLE) -> "no credentials" (ACTION_NEEDED) -> "platform unsupported"
        // (UNSUPPORTED) must all collapse to ONE page key, exactly like ArtworkFetchUi.Running's progress
        // ticks -- keying on tone would replay the fade on every live re-check flip, which is a strobe on
        // a slow connection, not a fix.
        val pending = mimirAchievementCardPageKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking))
        val unavailable = mimirAchievementCardPageKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.Unreachable))
        val actionNeeded = mimirAchievementCardPageKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials))
        val unsupported = mimirAchievementCardPageKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.SystemUnsupported))
        // Non-vacuity: prove these four reasons really do resolve to four DIFFERENT tones, so collapsing
        // them to one page key is a real decision and not an accident of them already sharing a tone.
        val tones = listOf(
            mimirAchievementUnknownTone(MimirAchievementCopy.Checking),
            mimirAchievementUnknownTone(MimirAchievementCopy.Unreachable),
            mimirAchievementUnknownTone(MimirAchievementCopy.NoCredentials),
            mimirAchievementUnknownTone(MimirAchievementCopy.SystemUnsupported),
        )
        assertEquals(
            "the four reasons used above must map to four DIFFERENT tones, or this test would prove " +
                "nothing about the collapsing decision",
            4,
            tones.toSet().size,
        )
        assertEquals(MimirAchievementCardPageKey.UNKNOWN, pending)
        assertEquals(pending, unavailable)
        assertEquals(pending, actionNeeded)
        assertEquals(pending, unsupported)
    }

    @Test
    fun `an unrecognised reason still resolves to UNKNOWN, not a crash or a new key`() {
        val key = mimirAchievementCardPageKey(MimirAchievementCardState.Unknown("some future string"))
        assertEquals(MimirAchievementCardPageKey.UNKNOWN, key)
    }

    @Test
    fun `Loaded with an empty row list is still the LOADED key, not a fourth page`() {
        // MimirAchievementCard's own `when` renders Loaded(rows = emptyList()) as a LedgerStatement, same
        // as Unknown/NotMatched -- but it is still the Loaded SUBCLASS, so the key must still be LOADED.
        // (The card's own comment calls this branch defensive/unreachable in production; the key function
        // must not special-case it either way.)
        val empty = mimirAchievementCardPageKey(sampleLoaded(rows = emptyList()))
        val nonEmpty = mimirAchievementCardPageKey(sampleLoaded())
        assertEquals(MimirAchievementCardPageKey.LOADED, empty)
        assertEquals(empty, nonEmpty)
    }

    private fun sampleLoaded(rows: List<MimirAchievementRow> = listOf(sampleRow())): MimirAchievementCardState.Loaded =
        MimirAchievementCardState.Loaded(
            gameTitle = "Ace Combat Advance",
            unlocked = 0,
            total = 43,
            earnedPoints = 0,
            totalPoints = 235,
            rows = rows,
        )

    private fun sampleRow(): MimirAchievementRow = MimirAchievementRow(
        id = 1,
        title = "Welded Woes",
        description = "Complete Mission #3 on any difficulty.",
        points = 25,
        medal = MimirMedalState.LOCKED,
    )
}

/**
 * LedgerHeadBand seam: the head band's game
 * title and points join their own, SEPARATE animated region from the card body -- the eyebrow stays put.
 * [mimirLedgerHeadBandTitleKey] is the content identity for that region: has-title vs no-title, not
 * per-title. Same pure-function-first discipline as [MimirAchievementCardPageKeyTest] above.
 */
class MimirLedgerHeadBandTitleKeyTest {
    @Test
    fun `Unknown and NotMatched both resolve to ABSENT`() {
        val unknown = mimirLedgerHeadBandTitleKey(MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking))
        val notMatched = mimirLedgerHeadBandTitleKey(MimirAchievementCardState.NotMatched("no set"))
        assertEquals(MimirLedgerHeadBandTitleKey.ABSENT, unknown)
        assertEquals(MimirLedgerHeadBandTitleKey.ABSENT, notMatched)
    }

    @Test
    fun `Loaded resolves to PRESENT`() {
        assertEquals(MimirLedgerHeadBandTitleKey.PRESENT, mimirLedgerHeadBandTitleKey(sampleLoaded()))
    }

    @Test
    fun `Loaded with an empty row list is still PRESENT -- the title does not depend on the rows`() {
        assertEquals(
            MimirLedgerHeadBandTitleKey.PRESENT,
            mimirLedgerHeadBandTitleKey(sampleLoaded(rows = emptyList())),
        )
    }

    @Test
    fun `two DIFFERENT Loaded games resolve to the SAME key -- pinning the PRESENT-ABSENT-only decision`() {
        // Deliberate, not an oversight: LedgerHeadBand's own KDoc explains why this key does NOT
        // discriminate on gameTitle -- MimirAchievementsCoordinator's openCard/openCardFromHome are only
        // reachable after dismissCard() has cleared openForGameId, so a genuinely new game always
        // re-enters through a fresh mount (starting at Unknown), never a live Loaded-to-Loaded jump this
        // key would need to catch. Non-vacuity: prove the two samples really do carry different titles, or
        // this test would prove nothing about the collapsing decision.
        val gameA = sampleLoaded(gameTitle = "Ace Combat Advance")
        val gameB = sampleLoaded(gameTitle = "Golden Sun")
        assertTrue(
            "the two samples must carry different titles, or this test proves nothing",
            gameA.gameTitle != gameB.gameTitle,
        )
        assertEquals(mimirLedgerHeadBandTitleKey(gameA), mimirLedgerHeadBandTitleKey(gameB))
        assertEquals(MimirLedgerHeadBandTitleKey.PRESENT, mimirLedgerHeadBandTitleKey(gameA))
    }

    private fun sampleLoaded(
        gameTitle: String = "Ace Combat Advance",
        rows: List<MimirAchievementRow> = listOf(sampleRow()),
    ): MimirAchievementCardState.Loaded =
        MimirAchievementCardState.Loaded(
            gameTitle = gameTitle,
            unlocked = 0,
            total = 43,
            earnedPoints = 0,
            totalPoints = 235,
            rows = rows,
        )

    private fun sampleRow(): MimirAchievementRow = MimirAchievementRow(
        id = 1,
        title = "Welded Woes",
        description = "Complete Mission #3 on any difficulty.",
        points = 25,
        medal = MimirMedalState.LOCKED,
    )
}

/**
 * Source-text pins, same anchored `sliceBetween` discipline `MimirMannazPinTest`/`MimirNorseBookTest`
 * already use in this package (a missing anchor fails loudly rather than degrading to a no-op pass).
 */
class MimirAchievementCardSourceTest {
    private val source: String by lazy { pinSource() }

    @Test
    fun `the phase AnimatedContent runs fade and resize on one clock`() {
        val block = source.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(
            "must key on a stable page enum, never the raw state (that is the strobe risk)",
            block.contains("contentKey = ::mimirAchievementCardPageKey"),
        )
        assertTrue(
            "the fade must run on the house duration token",
            block.contains("tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"),
        )
        assertTrue(
            "must supply an explicit SizeTransform so the resize shares the SAME clock as the fade, per " +
                "HomeTomeLibraryOverlay.kt:1301-1320's reference shape",
            block.contains(".using(") && block.contains("SizeTransform(clip = false)"),
        )
        // The clock must appear THREE times: incoming fade, outgoing fade, and the SizeTransform's own
        // spec -- one clock, three channels, exactly the reference sentence (and the app module's
        // ArtworkFetchOverlay/GetArtOverlay/MigrationImportOverlay pins of the same shape).
        val clockOccurrences = Regex("tween\\(MimirMotion\\.OverlayMs, easing = MimirMotion\\.Easing\\)")
            .findAll(block).count()
        assertEquals(
            "must run the incoming fade, the outgoing fade AND the resize on the SAME " +
                "tween(OverlayMs, Easing) -- found $clockOccurrences occurrences, expected 3",
            3,
            clockOccurrences,
        )
    }

    @Test
    fun `the setup action on a departing page cannot open Settings`() {
        // Product review 2026-09-28 (F3) gave the card its one control, the NEEDS SETUP action on the Unknown page.
        // A departing page is still composed for its fade, so its tap must check it is still the live page. This
        // replaces the pass-two pin that said no guard was needed because the card had no callback at all.
        val contentLambda = source.sliceBetween(") { page ->", "\n            }\n\n            MimirLedgerRule(palette = palette, pulse = false")
        assertTrue(
            "the setup action must act only from the live page",
            contentLambda.contains("onOpen = { if (page == state) onOpenProfile() }"),
        )
        assertFalse(
            "the page itself needs no clearAndSetSemantics guard; the action carries its own click",
            contentLambda.contains("clearAndSetSemantics"),
        )
    }

    @Test
    fun `LedgerBody is a plain composable, not a ColumnScope extension`() {
        // AnimatedContent's content lambda has an AnimatedContentScope receiver, not ColumnScope, so
        // LedgerBody can no longer be a `ColumnScope` extension function the way it was pre-pass-two --
        // its own weight(1f, fill = false) moved out to the AnimatedContent that now wraps its call site.
        assertFalse(
            "LedgerBody must no longer be declared as a ColumnScope extension",
            source.contains("private fun ColumnScope.LedgerBody("),
        )
        assertTrue(
            "LedgerBody must still exist as a plain composable",
            source.contains("private fun LedgerBody("),
        )
        val ledgerBodyBlock = source.sliceBetween("private fun LedgerBody(", "// Every scroll-derived read below")
        assertFalse(
            "the weight() modifier must have moved OUT of LedgerBody's own Box -- applying weight() here " +
                "would fail to compile (no ColumnScope in an AnimatedContentScope lambda) and, if it did " +
                "compile, would no longer bound anything at the panel's outer Column level",
            ledgerBodyBlock.contains("weight(1f, fill = false)"),
        )
    }

    @Test
    fun `the panel-level weight now sits on the AnimatedContent that wraps LedgerBody's call site`() {
        val block = source.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(
            "AnimatedContent must carry the weight(1f, fill = false) that used to live on LedgerBody's Box",
            block.contains("modifier = Modifier.weight(1f, fill = false)"),
        )
    }

    // ---- LedgerHeadBand seam ----
    //
    // This file now has TWO `AnimatedContent(` blocks: the card body's (above, `label =
    // "achievement-card-phase"`, tested above) and LedgerHeadBand's own conditional title/points region
    // (`label = "achievement-card-headband-title"`), defined LATER in the file. A `sliceBetween
    // ("AnimatedContent(", "label = ")` called on the raw, unscoped source would match the FIRST
    // occurrence of each anchor -- i.e. it would silently re-validate the body's block and pass vacuously
    // even if the head band's block were wrong or missing entirely. HomeTileMenuOverlay.kt hit exactly
    // this trap in PR #165 (ArtworkPhaseSequenceGrammarTest.kt, `HomeTileMenuOverlay's inner phase
    // AnimatedContent...`) and solved it the same way used here: scope PAST the body's own already-known
    // label anchor FIRST, so the first "AnimatedContent(" found afterwards cannot be the body's.

    private val pastBodyBlock: String by lazy {
        assertTrue(
            "the body AnimatedContent's own label anchor must be present, or this scope-past would not " +
                "be anchored to anything real",
            source.contains("label = \"achievement-card-phase\","),
        )
        source.substringAfter("label = \"achievement-card-phase\",")
    }

    @Test
    fun `the head band's title AnimatedContent is a DIFFERENT block from the body's -- disambiguation is real`() {
        // Non-vacuity for the disambiguation itself: prove the block reached by scoping past the body's
        // label is genuinely the head band's OWN block, not the body's block matched a second time.
        val bodyBlock = source.sliceBetween("AnimatedContent(", "label = ")
        val headBandBlock = pastBodyBlock.sliceBetween("AnimatedContent(", "label = ")
        assertFalse(
            "the head-band-scoped block must not be textually identical to the body's own block, or the " +
                "scope-past did nothing and both slices are reading the same AnimatedContent",
            headBandBlock == bodyBlock,
        )
        assertTrue(
            "the body's block keys on the card-page enum",
            bodyBlock.contains("contentKey = ::mimirAchievementCardPageKey"),
        )
        assertTrue(
            "the head band's own block must key on ITS OWN enum, not the body's -- proving this really is " +
                "the second AnimatedContent and not the first one re-matched",
            headBandBlock.contains("contentKey = ::mimirLedgerHeadBandTitleKey"),
        )
        assertFalse(
            "the head-band-scoped block must not carry the body's page-key reference",
            headBandBlock.contains("contentKey = ::mimirAchievementCardPageKey"),
        )
    }

    @Test
    fun `the head band's title AnimatedContent runs fade and resize on one clock`() {
        val block = pastBodyBlock.sliceBetween("AnimatedContent(", "label = ")
        assertTrue(
            "must key on the head band's own stable title-presence enum, never the raw state",
            block.contains("contentKey = ::mimirLedgerHeadBandTitleKey"),
        )
        assertTrue(
            "the fade must run on the house duration token",
            block.contains("tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"),
        )
        assertTrue(
            "must supply an explicit SizeTransform so the resize shares the SAME clock as the fade -- a " +
                "bare togetherWith would import the default SPRING size clock (spec §5c), exactly the " +
                "trap PR #165 hit one file over in HomeTileMenuOverlay.kt",
            block.contains(".using(") && block.contains("SizeTransform(clip = false)"),
        )
        val clockOccurrences = Regex("tween\\(MimirMotion\\.OverlayMs, easing = MimirMotion\\.Easing\\)")
            .findAll(block).count()
        assertEquals(
            "must run the incoming fade, the outgoing fade AND the resize on the SAME " +
                "tween(OverlayMs, Easing) -- found $clockOccurrences occurrences, expected 3",
            3,
            clockOccurrences,
        )
    }

    @Test
    fun `the eyebrow stays outside the head band's AnimatedContent -- only the conditional title-points joined it`() {
        // Only the conditional part -- game title and points -- joins the
        // animated region. The eyebrow stays put. Wrapping the whole band was explicitly rejected because
        // the eyebrow would then crossfade with itself on every phase change.
        val headBandFunctionBlock = source.sliceBetween(
            "private fun LedgerHeadBand(",
            "/** A statement of fact",
        )
        val eyebrowIndex = headBandFunctionBlock.indexOf("MimirAchievementCopy.CardEyebrow")
        val animatedContentIndex = headBandFunctionBlock.indexOf("AnimatedContent(")
        assertTrue("CardEyebrow must be present in LedgerHeadBand", eyebrowIndex >= 0)
        assertTrue("AnimatedContent must be present in LedgerHeadBand", animatedContentIndex >= 0)
        assertTrue(
            "the eyebrow's Text call must be written BEFORE the AnimatedContent block starts -- it is not " +
                "a child of it",
            eyebrowIndex < animatedContentIndex,
        )
        // And, the stronger check: the eyebrow string must not appear again INSIDE the AnimatedContent
        // block itself (i.e. it is not duplicated into both the outgoing and incoming pages).
        val insideAnimatedContent = headBandFunctionBlock.substringAfter("AnimatedContent(")
        assertFalse(
            "CardEyebrow must not be drawn again inside the AnimatedContent block -- only ONE eyebrow, " +
                "outside the animated region",
            insideAnimatedContent.contains("MimirAchievementCopy.CardEyebrow"),
        )
    }

    @Test
    fun `the head band's title AnimatedContent still carries the two-line title cap, unchanged`() {
        val block = pastBodyBlock.sliceBetween(
            "AnimatedContent(",
            "        val status = mimirAchievementStatusLabel",
        )
        assertTrue(
            "the ONE deliberate cap on this card (LedgerTitleStyle's maxLines = 2) must survive the move " +
                "into the AnimatedContent content lambda, unchanged",
            block.contains("maxLines = 2"),
        )
        assertTrue(block.contains("overflow = TextOverflow.Ellipsis"))
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun pinSource(): String {
        val name = "MimirAchievementCard.kt"
        val candidates = listOf(
            java.io.File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
