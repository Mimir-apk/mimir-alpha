package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirNorseBookTest {
    @Test
    fun `shared book consumes every authored hero role and keeps nine signatures`() {
        val treatments = MimirThemePalette.all.map { palette ->
            val treatment = mimirNorseBookTreatment(palette)
            val hero = palette.materials.hero

            assertEquals(hero.coverTop, treatment.coverTop)
            assertEquals(hero.coverMiddle, treatment.coverMiddle)
            assertEquals(hero.coverBottom, treatment.coverBottom)
            assertEquals(hero.onCover, treatment.onCover)
            assertEquals(hero.hardware, treatment.hardware)
            assertEquals(palette.materials.counterAccent, treatment.counterAccent)
            assertEquals(palette.runeMark, treatment.runeInk)
            assertTrue(
                "${palette.id} collapsed into a flat cover",
                listOf(
                    treatment.coverTop,
                    treatment.coverMiddle,
                    treatment.coverBottom,
                    treatment.hardware,
                    treatment.counterAccent,
                    treatment.runeInk,
                ).distinct().size >= 4,
            )
            treatment
        }

        assertEquals(9, treatments.size)
        assertEquals(9, treatments.map { it.signature }.distinct().size)
        assertEquals(0.75f, MimirNorseBookAspectRatio, 0f)
    }

    @Test
    fun `primary Tome proportions keep the transparent preview large and signatures subordinate`() {
        assertEquals(0.085f, MimirNorseBookTitleFraction, 0f)
        assertEquals(0.22f, MimirNorseBookFilledMarkFraction, 0f)
        assertEquals(32f, MimirNorseBookFilledMarkMaxDp, 0f)
        assertEquals(0.58f, MimirNorseBookMarkCenterYFraction, 0f)
        assertEquals(0.56f, MimirNorseBookPlateFraction, 0f)
        assertEquals(104f, MimirNorseBookPlateMaxDp, 0f)
        assertEquals(1f, MimirNorseBookPlateAspectRatio, 0f)
        assertEquals(0.42f, MimirNorseBookPlateSafeTopFraction, 0f)
        assertTrue(MimirNorseBookPlateSafeTopFraction < MimirNorseBookPlateSafeBottomFraction)
        assertEquals(0.08f, MimirNorseBookSignatureFraction, 0f)
        assertEquals(11f, MimirNorseBookSignatureMaxDp, 0f)
        assertTrue(MimirNorseBookFilledMarkFraction < MimirNorseBookEmptyMarkFraction)
        assertTrue(MimirNorseBookSignatureFraction < MimirNorseBookTitleFraction)
    }

    @Test
    fun `Mannaz uses one thin stroke law without a heavy size branch`() {
        assertEquals(0.030f, MimirNorseBookMannazStrokeFraction, 0f)
        assertEquals(1.25f, MimirNorseBookMannazStrokeMinDp, 0f)
        assertEquals(2f, MimirNorseBookMannazStrokeMaxDp, 0f)
        assertEquals(2f, mimirNorseBookMannazStrokePx(100f, 1.25f, 2f), 0.001f)
        assertEquals(1.25f, mimirNorseBookMannazStrokePx(20f, 1.25f, 2f), 0.001f)
    }

    /**
     * UI-04: the Files folio title lockup needs clearance at every tome aspect ratio.
     *
     * The plate's safe band has to begin below the title lockup, and the lockup is anchored to WIDTH in
     * every term it has. The band's start was a fraction of HEIGHT, which agrees with that at exactly
     * one proportion -- 3:4 -- and squeezes the mark up into the title at any shorter one.
     *
     * The two numbers below are the finishing pass's measurement, reproduced here so the fix is pinned
     * to evidence rather than to taste. Tome width ~118.2dp (the folio's own 4-column tome); the title
     * lockup's bottom edge lands at ~55.7dp, and at that width the old height-anchored band began at
     * 66.2dp on a 3:4 face (**~10.5dp of clearance**) but at only 56.6dp on the folio's 1 : 1.14
     * (**~0.9dp** -- effectively touching).
     */
    @Test
    fun `the plate safe top is width-anchored, so its title clearance no longer depends on the face's proportion`() {
        val width = 118.2f
        val shelfHeight = width / MimirNorseBookAspectRatio
        val folioAspect = 1f / 1.14f
        val folioHeight = width / folioAspect

        // 1. DEFAULT PATH IS THE OLD PATH, LITERALLY. Both Home tome faces call MimirNorseBookCover
        //    without naming aspectRatio, so this is the branch they take; it must evaluate exactly what
        //    the call site used to evaluate inline, on the same measured height.
        assertEquals(
            "at the default 3:4 the safe top must still be bookHeight * MimirNorseBookPlateSafeTopFraction " +
                "-- byte-identical, not merely close, or Home moves",
            shelfHeight * MimirNorseBookPlateSafeTopFraction,
            mimirNorseBookPlateSafeTopDp(width, shelfHeight, MimirNorseBookAspectRatio, compact = false),
            0f,
        )
        assertEquals(
            "the compact Home face takes the same default branch and must be equally untouched",
            shelfHeight * MimirNorseBookCompactPlateSafeTopFraction,
            mimirNorseBookPlateSafeTopDp(width, shelfHeight, MimirNorseBookAspectRatio, compact = true),
            0f,
        )

        // 2. THE LAW. Same width => same safe top, whatever the proportion. This is the whole fix: the
        //    band now shares the lockup's own anchor, so clearance stops being a property of 3:4.
        assertEquals(
            "a shorter face must get the SAME safe top as the shelf face at the same width -- if these " +
                "ever diverge, the band has gone back to being height-anchored and the mark starts " +
                "climbing into the title again",
            mimirNorseBookPlateSafeTopDp(width, shelfHeight, MimirNorseBookAspectRatio, compact = false),
            mimirNorseBookPlateSafeTopDp(width, folioHeight, folioAspect, compact = false),
            0.001f,
        )

        // 3. THE MEASUREMENT ITSELF, so a future edit has to argue with the numbers.
        val fixed = mimirNorseBookPlateSafeTopDp(width, folioHeight, folioAspect, compact = false)
        val heightAnchored = folioHeight * MimirNorseBookPlateSafeTopFraction
        assertEquals(66.19f, fixed, 0.05f)
        assertEquals(56.59f, heightAnchored, 0.05f)
        assertTrue(
            "the fix must give the folio tome back the ~9.6dp of title clearance the 1 : 1.14 ratio took " +
                "away (10.5dp at 3:4 vs 0.9dp at 1 : 1.14), found ${fixed - heightAnchored}dp",
            fixed - heightAnchored > 9f,
        )
    }

    /**
     * The wiring half of UI-04, and it exists because the test above **cannot fail on its own**: it calls
     * [mimirNorseBookPlateSafeTopDp] directly, so reverting the COMPOSABLE back to an inline
     * `bookHeight * fraction` leaves every assertion in it green while the folio tome regresses on the
     * device. That is precisely the "a test that still passes when its subject is removed" shape this
     * tree has been caught by three times in one day, so it gets its own pin rather than a note.
     *
     * Source-text, and honest about why: `MimirNorseBookCover` is a `@Composable` and this module runs
     * plain JUnit with no Compose harness. The established fallback in this repo is to read the call site
     * (`FileBrowserSupportDimRenderTest`, `CredentialFlowGlassContinuityTest`). It proves the composable
     * routes through the shared law; it does not prove a pixel. Only the device closes that.
     */
    @Test
    fun `the cover actually routes its plate safe top through the shared law`() {
        val safeTopBlock = norseBookSource().sliceBetween(
            after = "val safeTop =",
            before = "val safeBottom =",
        )
        assertTrue(
            "MimirNorseBookCover must compute safeTop via mimirNorseBookPlateSafeTopDp(...). An inline " +
                "`bookHeight * fraction` is the UI-04 defect itself -- height-anchored, while the title " +
                "lockup it has to clear is width-anchored -- and the geometry test above cannot see it, " +
                "because that test calls the function directly. Found: <${safeTopBlock.trim()}>",
            safeTopBlock.contains("mimirNorseBookPlateSafeTopDp("),
        )
        assertTrue(
            "the real aspectRatio parameter must be passed through, not the default constant -- passing " +
                "MimirNorseBookAspectRatio here would take the identity branch for every caller and " +
                "silently restore the height anchor for the Files folio",
            safeTopBlock.contains("aspectRatio = aspectRatio"),
        )
    }

    @Test
    fun `compact face keeps capped title and non-overlapping plate envelope`() {
        assertEquals(0.078f, MimirNorseBookCompactTitleFraction, 0f)
        assertEquals(0.038f, MimirNorseBookCompactEyebrowFraction, 0f)
        assertEquals(0.34f, MimirNorseBookCompactPlateSafeTopFraction, 0f)
        assertEquals(0.48f, MimirNorseBookCompactPlateFraction, 0f)
        assertTrue(MimirNorseBookCompactPlateSafeTopFraction < MimirNorseBookPlateSafeBottomFraction)
        assertTrue(MimirNorseBookCompactPlateFraction < MimirNorseBookPlateFraction)
        assertTrue(MimirNorseBookCompactTitleFraction < MimirNorseBookTitleFraction)
    }

    @Test
    fun `showLockup is additive - it defaults on, gates only the lockup, and leaves every other branch where it was`() {
        val source = norseBookSource()
        val signature = source.sliceBetween(after = "fun MimirNorseBookCover(", before = "val book = remember")
        assertTrue(
            "showLockup must default to true: every existing caller (both Home tome faces, the Files folio) renders its lockup byte-identically",
            signature.contains("showLockup: Boolean = true"),
        )
        // The lockless centre is its own constant, and the lockup-bearing branches below it are the ones they were.
        assertEquals(0.5f, MimirNorseBookLocklessMarkCenterYFraction, 0f)
        val centre = source.sliceBetween(after = "val markCenterYFraction =", before = "val titleSize =")
        assertTrue(
            "the lockless branch is first and the default 3:4 branch still reads the authored fraction",
            centre.indexOf("if (!showLockup)") in 0 until centre.indexOf("if (aspectRatio == MimirNorseBookAspectRatio)") &&
                centre.contains("MimirNorseBookMarkCenterYFraction"),
        )
        // The lockup is the only thing the flag gates: the eyebrow, its rule and the title sit in one Column inside `if (showLockup)`.
        val body = source.sliceBetween(after = "if (showLockup) {", before = "if (plate != null) {")
        assertTrue("the eyebrow is inside the gate", body.contains("eyebrow.uppercase()"))
        assertTrue("the title is inside the gate", body.contains("text = title,"))
        assertEquals("one gate, one lockup", 1, source.split("if (showLockup) {").size - 1)
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun norseBookSource(): String {
        val name = "MimirNorseBook.kt"
        val candidates = listOf(
            java.io.File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
