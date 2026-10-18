package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MimirShelfBackground`'s draw no longer runs the shelf builder for a palette-only change -- pinned as source.
 *
 * A profile on the AYN Thor (2026-09-11) showed that each first use of a theme
 * in a process skipped 33-46 frames, and a main-thread profile of the cold switch to crimson put 55.3% of its samples in
 * `MimirShelfBackgroundBitmapCache.getOrBuild` -> `bakeShelfScene`, called from this composable's `drawWithCache` block.
 * The fix is bookkeeping around code that still compiles and still paints if any one term is dropped, which is why the
 * terms are pinned here:
 *
 *  1. a CUTTING swap decides first -- only a palette-only change may keep the previous raster on screen, and Main cuts
 *     rather than crossfades (the required transition);
 *  2. the synchronous builder call survives only in `BAKE_NOW` -- a first bake, or a change of anything but the palette --
 *     and there it never reads a file: it bakes what `planSynchronousBake` allows, the Atmosphere while the player's image
 *     is not resident, and the image bakes off the UI thread next (`MimirSceneGroundSyncBakeTest` behaviour-tests the plan);
 *  3. a palette-only miss hands a bake built from plain values to `Dispatchers.Default`, publishes into the UI-thread-owned
 *     cache in the landing, and releases a stale or cancelled raster on both exits;
 *  4. the draw subscribes to adoptions, or an adopted raster waits for an unrelated invalidation.
 *
 * ## What this does NOT prove
 *
 * Nothing here renders a frame. The decision, the landing order and the cancellation release are behaviour-tested in
 * `MimirShelfBackgroundPaletteBakeTest` and `MimirShelfBackgroundStaleBakeTest`; whether the Thor stops skipping frames
 * on a cold switch is device-only.
 */
class MimirShelfBackgroundBakeWiringTest {

    /** `MimirShelfBackground`'s body, comments stripped (the KDoc quotes the old draw-time bake) and whitespace collapsed. */
    private fun shelfBackgroundBody(): String = bodyOf("fun MimirShelfBackground(")

    /** `mimirThemeSceneBackdrop`'s body -- the Bottom screen's and the compact browser's miniature -- the same way. */
    private fun themeSceneBackdropBody(): String = bodyOf("fun Modifier.mimirThemeSceneBackdrop(")

    private fun bodyOf(signature: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt")
        assertTrue(
            "MimirAtmosphere.kt not found at ${file.absolutePath} -- if it moved, move this pin with it rather than deleting it",
            file.isFile,
        )
        val code = file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val start = code.indexOf(signature)
        assertTrue("$signature is gone from MimirAtmosphere.kt", start >= 0)
        val end = Regex("""\n(?:private |internal )?(?:suspend )?fun """).find(code, start + 1)?.range?.first ?: code.length
        return code.substring(start, end).replace(Regex("""\s+"""), " ")
    }

    private fun String.occurrences(snippet: String): Int = Regex(Regex.escape(snippet)).findAll(this).count()

    @Test
    fun `a cutting swap decides before anything bakes`() {
        val body = shelfBackgroundBody()
        assertTrue(
            "the Main shelf holds its previous raster through a palette change and CUTS -- no crossfade on Main (§4.2(c))",
            "MimirSceneBackdropSwap<MimirShelfBackgroundCacheKey, ImageBitmap>(crossfadeMillis = 0)" in body,
        )
        assertTrue(
            "only a palette-only change may keep the previous raster on screen",
            "differsOnlyInPalette = MimirShelfBackgroundCacheKey::differsOnlyInPaletteFrom," in body,
        )
        assertTrue(
            "a cached palette is looked up without building",
            "cachedFor = shelfBackgroundBitmapCache::get," in body,
        )
    }

    @Test
    fun `the draw runs the builder synchronously only when nothing may stand in`() {
        val body = shelfBackgroundBody()
        assertFalse(
            "the unconditional draw-time bake is the main-thread stall of the cold theme switch",
            "val baked = shelfBackgroundBitmapCache.getOrBuild(" in body,
        )
        assertTrue(
            "the first bake and any non-palette change keep the synchronous path, so a cold start never draws empty -- " +
                "planned, so the key it is cached under always names the pixels painted",
            "MimirSceneBackdropStep.BAKE_NOW -> { " +
                "val plan = planSynchronousBake(cacheKey, rasterCached = { shelfBackgroundBitmapCache.get(it) != null }) " +
                "swap.showNow( plan.key, shelfBackgroundBitmapCache.getOrBuild(plan.key, estimatedBytes) { " +
                "bakeShelfBackgroundRaster(plan.key, bakeFontScale, stars) { plan.groundImage } }, )" in body,
        )
        assertEquals(
            "the synchronous builder call must appear exactly once -- in BAKE_NOW",
            1,
            body.occurrences("shelfBackgroundBitmapCache.getOrBuild(plan.key, estimatedBytes)"),
        )
        assertFalse(
            "the old unplanned synchronous call can decode a ground on the UI thread",
            "getOrBuild(cacheKey, estimatedBytes, bake)" in body,
        )
        assertFalse("the draw must never invoke the bake itself", "bake()" in body)
    }

    @Test
    fun `a UI-thread bake never decodes and hands a pending image to the off-thread path`() {
        val body = shelfBackgroundBody()
        assertEquals(
            "getOrDecode may only be named where a decode may block: the off-thread bake's lookup, once",
            1,
            body.occurrences("MimirSceneGroundImages::getOrDecode"),
        )
        assertTrue(
            "and that one is the bake handed to Dispatchers.Default",
            "val bake = { bakeShelfBackgroundRaster(cacheKey, bakeFontScale, stars, MimirSceneGroundImages::getOrDecode) }" in body,
        )
        val syncArm = body.substringAfter("MimirSceneBackdropStep.BAKE_NOW -> {").substringBefore("MimirSceneBackdropStep.BAKE_OFF_THREAD -> bakeOffThread()")
        assertFalse("the synchronous arm must not decode", "getOrDecode" in syncArm)
        assertTrue(
            "a pending image asks the swap again for the requested key -- a ground-only change, which bakes off-thread -- " +
                "and starts that bake",
            "if (plan.groundPending && requestRaster() == MimirSceneBackdropStep.BAKE_OFF_THREAD) bakeOffThread()" in syncArm,
        )
    }

    @Test
    fun `the miniature's UI-thread bake never decodes either`() {
        val body = themeSceneBackdropBody()
        assertEquals(1, body.occurrences("MimirSceneGroundImages::getOrDecode"))
        assertTrue(
            "the off-thread miniature bake is the only place a decode may block",
            "val bake = { bakeSceneBackdropMiniature( bakeSize, bakeDensity, bakeLayoutDirection, stars, palette, radius, ground, " +
                "MimirSceneGroundImages::getOrDecode, quietSceneLight, shineStars, ) }" in body,
        )
        assertTrue(
            "the first bake is planned: the Atmosphere while the image is not resident, cached under the key it paints",
            "MimirSceneBackdropStep.BAKE_NOW -> { " +
                "val plan = planSynchronousBake(cacheKey, rasterCached = { sceneBackdropBitmapCache.get(it) != null }) " +
                "swap.showNow( plan.key, sceneBackdropBitmapCache.getOrBuild(plan.key, estimatedBytes) { " +
                "bakeSceneBackdropMiniature( bakeSize, bakeDensity, bakeLayoutDirection, stars, palette, radius, plan.key.ground, " +
                "groundImageOf = { plan.groundImage }, quietSceneLight = quietSceneLight, shineStars = shineStars, ) }, )" in body,
        )
        assertFalse(
            "the old unplanned synchronous call can decode a ground on the UI thread",
            "getOrBuild(cacheKey, estimatedBytes, bake)" in body,
        )
        assertTrue(
            "the pending image lands on the swap's own clock -- a crossfade, or a cut under reduced motion",
            "if (plan.groundPending && requestRaster() == MimirSceneBackdropStep.BAKE_OFF_THREAD) bakeOffThread()" in body,
        )
    }

    @Test
    fun `a palette-only miss bakes on Default from plain values and publishes on the UI thread`() {
        val body = shelfBackgroundBody()
        assertTrue(
            "the bake reads only the key and the font scale that completes its Density -- never this CacheDrawScope, " +
                "which the next pass reuses, nor state that moved after the key was built",
            "val bake = { bakeShelfBackgroundRaster(cacheKey, bakeFontScale, stars, MimirSceneGroundImages::getOrDecode) }" in body,
        )
        assertTrue(
            "a palette-only miss must hand that bake to Dispatchers.Default",
            "val bakeOffThread = { scope.launch { runOffThreadShelfBake( " +
                "bakeContext = Dispatchers.Default, bake = bake," in body,
        )
        assertTrue(
            "and only BAKE_OFF_THREAD (and a pending ground, above) starts it",
            "MimirSceneBackdropStep.BAKE_OFF_THREAD -> bakeOffThread()" in body,
        )
        assertTrue(
            "the UI-thread-owned cache is written in the landing, after the hop back -- never inside the bake",
            "shelfBackgroundBitmapCache.getOrBuild(key, estimatedBytes) { result }" in body,
        )
        assertEquals(
            "a stale, duplicate or cancelled raster is released on both exits -- the landing and the cancellation",
            2,
            body.occurrences("release = ::releaseUnpublishedShelfRaster,"),
        )
    }

    @Test
    fun `the draw subscribes to adoptions and paints the swap's raster`() {
        val body = shelfBackgroundBody()
        assertTrue(
            "without the read, an adopted raster waits for an unrelated invalidation to show",
            "onDrawBehind { adoptedRasters.intValue swap.shown?.let { drawImage(it) } }" in body,
        )
        assertTrue("an adoption must bump the draw's subscription", "adoptedRasters.intValue++" in body)
    }
}
