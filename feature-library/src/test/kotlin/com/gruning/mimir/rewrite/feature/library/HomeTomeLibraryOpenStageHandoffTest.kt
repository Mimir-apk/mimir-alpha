package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP01 beat 3  — **the
 * shared backdrop stage must not hand off to the Tome Library's art before the Library is opaque.**
 *
 * Evidence : 134ms after the shelf's
 * last settled frame, mean luma jumped 42.8 -> 56.0 (+31%) in one frame -- the shared stage had already
 * swapped to the Library's (bright) art while the Library's own layer alpha was still near 0, with none
 * of the Library's own darkening scrims over it yet. `libraryArt` was first priority in
 * [homeShelfTomeStageArt] and gated on nothing; the neighbouring Folio hop already had this exact gate
 * via [homeShelfFolioStageHandedOff] (RC-34). This file pins the mirror gate for the Library.
 *
 * Same limitation [HomeTomeOpenStageHandoffTest] states: no Compose harness in this module, so nothing
 * here proves the felt result -- these pin the reducer and the structure; device capture is
 * what confirms G3 (the luma envelope) on hardware.
 */
class HomeTomeLibraryOpenStageHandoffTest {

    private val libraryArt = "file:///tomes/nes/bravely-second.png"
    private val collectionArt = "file:///tomes/snes/cover-lead.png"
    private val memberArt = "file:///games/chrono-trigger/hero.png"

    // ── The gate: presence is not settlement ────────────────────────────────────────────────────────

    @Test
    fun `the settle latch alone decides -- there is no separate presence input to forget`() {
        assertFalse(homeShelfLibraryStageHandedOff(libraryOpenSettled = false))
        assertTrue(homeShelfLibraryStageHandedOff(libraryOpenSettled = true))
    }

    // ── The stage art ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the OUTGOING hero is banished for the whole open, not blurred and carried through it`() {
        // Policy 2026-09-03. Before this, the open fell through to tomeLeadArt -- the art of the
        // surface being LEFT -- and CP01 beat 1 blurred it rather than removing it. Measured at 60fps on
        // the Thor: 117 ms of a bright detail-free wash, backdrop luma 36.6 -> 45.2 while detail sat at
        // ~0, then a 23% drop to 36.8 as the Tome's own art arrived. The hop ended darker than its middle.
        assertNull(
            "during the open the stage must show NOTHING rather than the departing surface's hero, even " +
                "though tomeLeadArt is sitting right there to fall through to",
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
                libraryStageHandedOff = false,
                libraryOpening = true,
            ),
        )
        assertEquals(
            "and once the landing beat has passed, the Library's own art takes the stage",
            libraryArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
                libraryStageHandedOff = true,
                libraryOpening = true,
            ),
        )
    }

    @Test
    fun `banishing is scoped to the open and changes nothing outside it`() {
        // The Folio hop, the collection lead art and the plain shelf must all be byte-identical. This
        // boundary is about ONE window; a stage that went blank outside it would be a new defect.
        assertEquals(
            "with the Library not opening, the collection's lead art still wins as it always has",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = null,
                libraryStageHandedOff = false,
                libraryOpening = false,
            ),
        )
        assertEquals(
            "the Folio's own settled handoff is untouched",
            "file:///member.png",
            homeShelfTomeStageArt(
                folioStageHandedOff = true,
                folioArt = "file:///member.png",
                tomeLeadArt = null,
                libraryArt = null,
                libraryStageHandedOff = false,
                libraryOpening = false,
            ),
        )
    }

    @Test
    fun `libraryOpening defaults to false so an unwired host keeps today's behaviour`() {
        // Same discipline as libraryStageHandedOff's own default: a caller that has not been taught the
        // new window must not silently start blanking its stage.
        assertEquals(
            "an unwired caller must fall through to the collection art exactly as before",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
                libraryStageHandedOff = false,
            ),
        )
    }

    @Test
    fun `the Library's art is withheld from the stage until its own arrival settles`() {
        assertNull(
            "Before this gate, libraryArt was first-priority and unconditional -- exactly the +31% " +
                "luma-flash mechanism: the shared stage swapping to the Library's art while the Library " +
                "itself was still near alpha 0. With no fallback art either, withholding it must not " +
                "invent something else to show.",
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = null,
                libraryArt = libraryArt,
                libraryStageHandedOff = false,
            ),
        )
        assertEquals(
            "and once the Library's own arrival is opaque, its art wins -- it is the surface actually on " +
                "top.",
            libraryArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
                libraryStageHandedOff = true,
            ),
        )
    }

    @Test
    fun `while withheld the stage falls through to the collection lead, never to nothing`() {
        assertEquals(
            "The stage stays MOUNTED across the Library's open ramp (homeShelfTomeStageMounted already " +
                "covers tomeLibraryActive); this is what makes the collection art reachable as the " +
                "in-between frame instead of an unmount.",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
                libraryStageHandedOff = false,
            ),
        )
    }

    @Test
    fun `an unwired caller keeps today's unconditional priority`() {
        assertEquals(
            "libraryStageHandedOff defaults to true -- an unwired host must not silently regress to " +
                "withholding libraryArt forever.",
            libraryArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = true,
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
                libraryArt = libraryArt,
            ),
        )
    }

    @Test
    fun `the two gates are independent -- withholding the Library never revives a not-yet-settled Folio`() {
        assertNull(
            "Both gates closed: neither surface has handed off, and the fallback chain still ends at " +
                "null rather than exposing an unsettled Folio's member art.",
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = memberArt,
                tomeLeadArt = null,
                libraryArt = libraryArt,
                libraryStageHandedOff = false,
            ),
        )
    }

    // ── a Library that landed on a game with no hero ──

    private val heroArt = "file:///media_art/chrono-hero.png"
    private val coverArt = "file:///media/chrono-cover.png"

    private fun preview(hero: String?, backdrop: String? = coverArt, physical: Boolean = false) = HomeTomePreview(
        artUri = coverArt,
        shape = if (physical) ShelfFaceShape.PHYSICAL else ShelfFaceShape.LEDGE_COVER,
        backdropArtUri = backdrop,
        heroArtUri = hero,
    )

    @Test
    fun `a Library that landed with no art for the stage leaves the stage on the theme, not the cover`() {
        assertNull(
            "the game has no hero: the Library shows the theme backdrop, so the stage must not fall through to its cover",
            homeShelfTomeStageArt(
                folioStageHandedOff = false,
                folioArt = null,
                tomeLeadArt = coverArt,
                libraryArt = null,
                libraryStageHandedOff = true,
                libraryOpening = false,
                libraryThemeOnly = true,
            ),
        )
    }

    @Test
    fun `with a hero the Library's art still wins, and a settled Folio still shows its own`() {
        assertEquals(
            heroArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false, folioArt = null, tomeLeadArt = coverArt,
                libraryArt = heroArt, libraryStageHandedOff = true, libraryThemeOnly = true,
            ),
        )
        assertEquals(
            memberArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = true, folioArt = memberArt, tomeLeadArt = coverArt,
                libraryArt = null, libraryStageHandedOff = true, libraryThemeOnly = true,
            ),
        )
    }

    @Test
    fun `libraryThemeOnly defaults to false so an unwired caller keeps the shelf's own ladder`() {
        assertEquals(
            coverArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = false, folioArt = null, tomeLeadArt = coverArt,
                libraryArt = null, libraryStageHandedOff = true,
            ),
        )
    }

    @Test
    fun `after a Library visit the Tome's lead art is the hero or the theme, never the cover`() {
        // No Library opened: the shelf's own ladder, hero then cover, exactly as before.
        assertEquals(coverArt, homeShelfTomeLeadArt(preview(hero = null), libraryVisited = false))
        assertEquals(heroArt, homeShelfTomeLeadArt(preview(hero = heroArt, backdrop = heroArt), libraryVisited = false))
        // After a visit: no hero is the theme (null); a hero is unchanged.
        assertNull(homeShelfTomeLeadArt(preview(hero = null), libraryVisited = true))
        assertNull(homeShelfTomeLeadArt(preview(hero = null, backdrop = null, physical = true), libraryVisited = true))
        assertEquals(heroArt, homeShelfTomeLeadArt(preview(hero = heroArt, backdrop = heroArt), libraryVisited = true))
        assertNull(homeShelfTomeLeadArt(null, libraryVisited = true))
    }

    @Test
    fun `the caller wires the theme-only rule from the Library's own landing and drops it when the selection moves`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertTrue(code.contains("libraryThemeOnly = tomeLibraryActive && libraryStageHandedOff,"))
        assertTrue(code.contains("val liveTomeLeadArt = homeShelfTomeLeadArt("))
        assertTrue(code.contains("libraryVisited = libraryVisited,"))
        assertTrue(
            "a visit is only recorded once the Library has handed off",
            code.contains("if (tomeLibraryActive && libraryStageHandedOff) libraryVisited = true"),
        )
        assertTrue(
            "and it is dropped when the selected Tome changes, so the shelf's own ladder returns",
            Regex("""LaunchedEffect\(selectedTome\?\.tomeId\) \{\s*if \(!tomeLibraryActive\) libraryVisited = false""")
                .containsMatchIn(code),
        )
    }

    // ── Structural pins: the parts no value test can see ────────────────────────────────────────────

    private fun source(relativePath: String): String {
        val file = File(relativePath)
        assertTrue(
            "$relativePath not found at ${file.absolutePath} — if this file moved, move this pin with " +
                "it rather than deleting it.",
            file.exists(),
        )
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `the Library's settle signal is a parameter from the layer that owns the opening, never a duration`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertTrue(
            "The settled test must arrive as a parameter, exactly like tomeFolioOpenSettled.",
            code.contains("tomeLibraryOpenSettled"),
        )
        assertTrue(
            "The screen must derive the hand-off through the reducer rather than inlining the conjunction.",
            code.contains("homeShelfLibraryStageHandedOff("),
        )
        // **NARROWED 2026-09-03, and narrowed to what it actually protects.** This forbade any
        // `delay(...Library...)` outright. The opacity handoff requires that the Tome's hero should land a beat
        // AFTER its surface is opaque, which needs a duration -- and the honest question is whether that
        // is the thing this pin exists to stop. It is not.
        //
        // The hazard named above is a duration standing BESIDE the arrival as a rival source of truth:
        // `delay(hold + OverlayMs)` racing the tween, drifting under a dropped frame, handing off while
        // the cover is still translucent. A delay that starts FROM the settle latch is the opposite
        // dependency -- it cannot fire before the thing it waits on, and it cannot drift when the
        // opening's spec changes, because it does not model the opening at all.
        //
        // So the pin now checks the DIRECTION rather than the presence: every library-scoped delay must
        // live inside the effect keyed on the real settle signal. Renaming the constant to dodge the old
        // regex would have been the dishonest way past this, and is why the rule is restated rather than
        // deleted.
        val settleEffect = Regex(
            """LaunchedEffect\(tomeLibraryOpenSettled\)\s*\{(.*?)\n {8}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(code)?.groupValues?.get(1)
        assertTrue(
            "the landing beat must live in an effect keyed on tomeLibraryOpenSettled -- that keying is " +
                "what makes it follow the arrival instead of racing it",
            settleEffect != null,
        )
        val libraryDelays = Regex("""delay\([^)]*[Ll]ibrary""").findAll(code).count()
        val delaysInsideSettle = Regex("""delay\([^)]*[Ll]ibrary""")
            .findAll(settleEffect.orEmpty()).count()
        assertEquals(
            "every library-scoped delay must sit INSIDE the settle-keyed effect. One outside it is the " +
                "rival-clock defect this pin was written for: it would gate the hand-off on a guess " +
                "rather than on the arrival's own alpha.",
            libraryDelays,
            delaysInsideSettle,
        )
        assertTrue(
            "the effect must still be GUARDED by the settle signal, not merely keyed on it -- an " +
                "unguarded body would run its delay on the false edge too and land the art while the " +
                "Library is closing",
            Regex("""if \(!tomeLibraryOpenSettled\)""").containsMatchIn(settleEffect.orEmpty()),
        )
    }

    @Test
    fun `the settle gate takes exactly one input -- no presence flag to AND it with`() {
        // homeShelfLibraryStageHandedOff is deliberately unary, unlike homeShelfFolioStageHandedOff's
        // (folioActive, folioOpenSettled) pair -- see its own kdoc. ANDing with a live "is the Library
        // still open" presence flag would re-gate libraryArt OFF the instant the Library starts closing,
        // reintroducing the flash on the RETURN direction this ticket does not touch. This pin fails
        // loudly if a future edit widens the signature to match the Folio's shape.
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertTrue(
            "homeShelfLibraryStageHandedOff must declare exactly one Boolean parameter",
            Regex(
                """internal fun homeShelfLibraryStageHandedOff\(libraryOpenSettled: Boolean\): Boolean""",
            ).containsMatchIn(code),
        )
    }

    @Test
    fun `the caller does not serve the shelf's still back while the Library opens`() {
        // a hero flashed behind the opening Tome collection. The pure function nulled
        // the art, but the caller's fallback put the focused row's still straight back.
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertTrue(
            "resolvedStill must be null while the Library opens",
            Regex("""val resolvedStill = if \(libraryOpening\) \{\s*null\s*\} else \{""").containsMatchIn(code),
        )
        assertTrue(
            "the null must reach the live host so it fades out rather than being disposed",
            code.contains("stillUri != null || libraryOpening -> HomeShelfStaticBackdrop("),
        )
    }
}
