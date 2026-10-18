package com.gruning.mimir.rewrite.feature.companion

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.BottomUiArtState
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom's art law: wired art that is loading draws NOTHING, wired art that failed
 * draws NOTHING, and the honest fallback is only for nothing wired. The table is written as FACTS, not recomputed from the function.
 *
 * The device-level acceptance, which a unit cannot give, is the receipts: zero `breach=true` and no
 * `art.plate tile=quick.<pkg> hasArt=true state=loading plate=visible` on a cold start, the lattice's first page, the dock and Tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionArtDrawTest {

    @get:Rule
    val compose = createComposeRule()

    private val icon: ImageBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Before
    @After
    fun emptyTheCache() {
        companionAppIconCache.evictAll()
    }

    // ---- the tri-state ---------------------------------------------------------------------------------------------

    @Test
    fun `an outer null is loading, an inner null is failed, a bitmap is ready`() {
        assertEquals(CompanionArtLoad.LOADING, companionArtLoad(null))
        assertEquals(CompanionArtLoad.FAILED, companionArtLoad(CompanionDecodedArt(null)))
        assertEquals(CompanionArtLoad.READY, companionArtLoad(CompanionDecodedArt(icon)))
    }

    // ---- the draw table: four states, wired and unwired ------------------------------------------------------------

    @Test
    fun `ready draws the art`() {
        assertEquals(CompanionArtDraw.ART, companionArtDraw(CompanionArtLoad.READY, wired = true))
    }

    @Test
    fun `loading draws nothing`() {
        assertEquals(CompanionArtDraw.NOTHING, companionArtDraw(CompanionArtLoad.LOADING, wired = true))
    }

    @Test
    fun `failed draws nothing`() {
        assertEquals(CompanionArtDraw.NOTHING, companionArtDraw(CompanionArtLoad.FAILED, wired = true))
    }

    @Test
    fun `nothing wired draws the honest fallback in every load state`() {
        CompanionArtLoad.values().forEach { load ->
            assertEquals("unwired, $load", CompanionArtDraw.FALLBACK, companionArtDraw(load, wired = false))
        }
    }

    // ---- R8a: a pick whose file is gone is unwired -----------------------------------------------------------------

    @Test
    fun `a source whose file is gone is not wired, and a source with no path never was`() {
        val gone = CompanionDecodedArt(bitmap = null, sourceMissing = true)
        assertFalse(companionArtWired(hasSource = true, decoded = gone))
        assertEquals(
            CompanionArtDraw.FALLBACK,
            companionArtDraw(companionArtLoad(gone), companionArtWired(hasSource = true, decoded = gone)),
        )
        assertFalse(companionArtWired(hasSource = false, decoded = null))
    }

    @Test
    fun `a source that is loading or failed is still wired`() {
        assertTrue(companionArtWired(hasSource = true, decoded = null))
        assertTrue("an existing file that fails to decode is wired and failed", companionArtWired(hasSource = true, decoded = CompanionDecodedArt(null)))
    }

    @Test
    fun `the decode finds a missing file unwired, an undecodable file failed, and caches a good one`() = runBlocking {
        val gone = decodeCompanionIcon("/no/such/icon.png", wired = { false }, decode = { error("a missing file is not decoded") })
        assertTrue(gone.sourceMissing)
        assertNull(gone.bitmap)

        val corrupt = decodeCompanionIcon("/data/corrupt.png", wired = { true }, decode = { null })
        assertFalse("an existing file is not 'missing'", corrupt.sourceMissing)
        assertNull(corrupt.bitmap)
        assertNull("a failed decode is never cached", companionAppIconCache.get("/data/corrupt.png"))

        val good = decodeCompanionIcon("/data/good.png", wired = { true }, decode = { icon })
        assertSame(icon, good.bitmap)
        assertSame(icon, companionAppIconCache.get("/data/good.png"))
    }

    // ---- the receipt facts: the harness grammar is state=none|loading|loaded|failed -------------------------------------

    /** The `art.plate` line a site writes for these facts, exactly as the receipts parser reads it. */
    private fun line(load: CompanionArtLoad, wired: Boolean): String {
        val facts = companionArtReceiptFacts(load, wired)
        return BottomUiReceiptFormat.artPlate(
            tile = "platform.snes",
            display = 4,
            hasArt = facts.hasArt,
            state = BottomUiArtState.of(facts.hasArt, facts.loaded, facts.failed),
            plateVisible = facts.plateVisible,
        )
    }

    @Test
    fun `a wired icon that is loading reads hasArt true state loading and plate hidden, never a breach`() {
        assertEquals(
            CompanionArtReceiptFacts(hasArt = true, loaded = false, failed = false, plateVisible = false),
            companionArtReceiptFacts(CompanionArtLoad.LOADING, wired = true),
        )
        assertEquals("ev=art.plate tile=platform.snes display=4 hasArt=true state=loading plate=hidden", line(CompanionArtLoad.LOADING, wired = true))
    }

    @Test
    fun `a loaded icon reads hasArt true state loaded and plate hidden`() {
        assertEquals(
            CompanionArtReceiptFacts(hasArt = true, loaded = true, failed = false, plateVisible = false),
            companionArtReceiptFacts(CompanionArtLoad.READY, wired = true),
        )
        assertEquals("ev=art.plate tile=platform.snes display=4 hasArt=true state=loaded plate=hidden", line(CompanionArtLoad.READY, wired = true))
    }

    @Test
    fun `an unwired site reads hasArt false state none and plate visible`() {
        assertEquals(
            CompanionArtReceiptFacts(hasArt = false, loaded = false, failed = false, plateVisible = true),
            companionArtReceiptFacts(CompanionArtLoad.LOADING, wired = false),
        )
        assertEquals("ev=art.plate tile=platform.snes display=4 hasArt=false state=none plate=visible", line(CompanionArtLoad.LOADING, wired = false))
    }

    @Test
    fun `a failed wired art reads hasArt true state failed and plate hidden with no breach`() {
        // The receipts contract (D1): wired is `hasArt=true` however the load ended, so the harness can tell a failed art from none. Under
        // R8 a failed art draws nothing, so the plate is hidden and the line carries no `breach=true`.
        assertEquals(
            CompanionArtReceiptFacts(hasArt = true, loaded = false, failed = true, plateVisible = false),
            companionArtReceiptFacts(CompanionArtLoad.FAILED, wired = true),
        )
        val failed = line(CompanionArtLoad.FAILED, wired = true)
        assertEquals("ev=art.plate tile=platform.snes display=4 hasArt=true state=failed plate=hidden", failed)
        assertFalse(failed.contains("breach"))
    }

    @Test
    fun `no state of any site is a breach`() {
        CompanionArtLoad.values().forEach { load ->
            listOf(true, false).forEach { wired ->
                val facts = companionArtReceiptFacts(load, wired)
                assertFalse("$load wired=$wired", facts.hasArt && facts.plateVisible)
                assertFalse("$load wired=$wired", line(load, wired).contains("breach"))
            }
        }
    }

    // ---- the Tabs source chain ---------------------------------------------------------------------------------------

    @Test
    fun `a decoded cover or icon is the art, whatever else failed`() {
        assertSame(icon, companionTabArtOutcome(coverWired = true, cover = icon, icon = null).bitmap)
        val iconArt = CompanionDecodedArt(icon)
        assertSame(iconArt, companionTabArtOutcome(coverWired = true, cover = null, icon = iconArt))
    }

    @Test
    fun `a deleted local cover and an absent or deleted icon give the fallback, not a failure`() {
        // P2 CompanionRecentsDrawer: every wired source is gone, so the tab is UNWIRED and draws its letter.
        val noIconPath = companionTabArtOutcome(coverWired = false, cover = null, icon = null)
        val deletedIcon = companionTabArtOutcome(coverWired = false, cover = null, icon = CompanionDecodedArt(null, sourceMissing = true))
        listOf(noIconPath, deletedIcon).forEach { outcome ->
            assertTrue(outcome.sourceMissing)
            assertEquals(CompanionArtDraw.FALLBACK, companionArtDraw(companionArtLoad(outcome), companionArtWired(hasSource = true, decoded = outcome)))
        }
    }

    @Test
    fun `a wired source that did not decode is a failure that draws nothing`() {
        val coverFailed = companionTabArtOutcome(coverWired = true, cover = null, icon = CompanionDecodedArt(null, sourceMissing = true))
        val iconFailed = companionTabArtOutcome(coverWired = false, cover = null, icon = CompanionDecodedArt(null))
        listOf(coverFailed, iconFailed).forEach { outcome ->
            assertFalse(outcome.sourceMissing)
            assertEquals(CompanionArtLoad.FAILED, companionArtLoad(outcome))
            assertEquals(CompanionArtDraw.NOTHING, companionArtDraw(companionArtLoad(outcome), companionArtWired(hasSource = true, decoded = outcome)))
        }
    }

    // ---- the icon memory cache: a second read is non-null on frame 0 -----------------------------------------------

    @Test
    fun `a cached path is non-null on the second read`() {
        assertNull("cold: nothing cached yet", companionWarmIcon("/data/app.png"))
        companionAppIconCache.put("/data/app.png", icon)
        val warm = companionWarmIcon("/data/app.png")
        assertNotNull("the second read is warm", warm)
        assertSame(icon, warm?.bitmap)
        assertEquals(CompanionArtLoad.READY, companionArtLoad(warm))
    }

    @Test
    fun `a warm icon is ready on the first composition, with no decode and no frame of loading`() {
        companionAppIconCache.put("/data/warm.png", icon)
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent { seen += rememberCompanionIconArt("/data/warm.png", wired = { true }) }
        compose.waitForIdle()
        assertTrue("composed at least once", seen.isNotEmpty())
        assertSame("frame 0 already holds the bitmap", icon, seen.first()?.bitmap)
    }

    @Test
    fun `a cold icon is loading on the first composition`() {
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent { seen += rememberCompanionIconArt(null) }
        compose.waitForIdle()
        assertNull(seen.first())
    }

    // ---- the prewarm starts from a COLD cache, so a no-op warmer fails ------------------------------------------------

    @Test
    fun `the prewarm fills a cold cache, once, and a no-op warmer would fail this`() = runBlocking {
        assertNull("the cache starts cold", companionWarmIcon("/data/dock.png"))
        assertNull(companionAppIconCache.get("/data/dock.png"))
        var decodes = 0
        CompanionCoverArtWarmer.prewarmIcon("/data/dock.png", wired = { true }, decode = { decodes += 1; icon })
        assertEquals("the warmer decoded the cold path", 1, decodes)
        assertSame("and the cache now holds it", icon, companionWarmIcon("/data/dock.png")?.bitmap)

        CompanionCoverArtWarmer.prewarmIcon("/data/dock.png", wired = { true }, decode = { decodes += 1; icon })
        assertEquals("a path already warm is not decoded again", 1, decodes)
    }

    @Test
    fun `the prewarm leaves a gone file and an undecodable file cold`() = runBlocking {
        CompanionCoverArtWarmer.prewarmIcon("/data/gone.png", wired = { false }, decode = { error("not decoded") })
        CompanionCoverArtWarmer.prewarmIcon("/data/corrupt.png", wired = { true }, decode = { null })
        assertNull(companionWarmIcon("/data/gone.png"))
        assertNull(companionWarmIcon("/data/corrupt.png"))
    }

    // ---- warm hits are verified off-main (R8a) ---------------------------------------------------------------------------

    @Test
    fun `a warm hit whose file is gone drops the cache entry and answers the fallback`() = runBlocking {
        companionAppIconCache.put("/data/stale.png", icon)
        val verdict = companionVerifyWarm("/data/stale.png", wired = { false }, drop = { companionAppIconCache.remove("/data/stale.png") })
        assertNotNull(verdict)
        assertTrue(verdict!!.sourceMissing)
        assertNull("the stale entry is dropped", companionWarmIcon("/data/stale.png"))
    }

    @Test
    fun `a warm hit whose file is there stands and nothing is dropped`() = runBlocking {
        companionAppIconCache.put("/data/fine.png", icon)
        var dropped = false
        val verdict = companionVerifyWarm("/data/fine.png", wired = { true }, drop = { dropped = true })
        assertNull(verdict)
        assertFalse(dropped)
        assertNotNull(companionWarmIcon("/data/fine.png"))
    }

    @Test
    fun `the verification of a warm icon reaches the site and shows the fallback`() {
        companionAppIconCache.put("/data/deleted.png", icon)
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent { seen += rememberCompanionIconArt("/data/deleted.png", wired = { false }) }
        compose.waitUntil(5_000) { seen.lastOrNull()?.sourceMissing == true }
        assertNull("the cache entry is gone", companionWarmIcon("/data/deleted.png"))
    }

    // ---- the decode window: refreshed on entry, released outside ---------------------------------------------------------

    @Test
    fun `a site entering the decode window finds a cache that filled meanwhile, and a site outside holds nothing`() {
        var decode by mutableStateOf(false)
        var decodes = 0
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent {
            seen += rememberCompanionIconArt("/data/window.png", decode, wired = { decodes += 1; true })
        }
        compose.waitForIdle()
        assertNull("outside the window and cold: nothing", seen.last())

        companionAppIconCache.put("/data/window.png", icon) // another site decoded it while this one was outside
        decode = true
        compose.waitForIdle()
        assertSame("entering the window re-probes the cache and lands on the art", icon, seen.last()?.bitmap)
    }

    @Test
    fun `a warm hit respects decode false`() {
        companionAppIconCache.put("/data/outside.png", icon)
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent { seen += rememberCompanionIconArt("/data/outside.png", decode = false, wired = { true }) }
        compose.waitForIdle()
        assertTrue(seen.isNotEmpty())
        seen.forEach { assertNull("a tile outside the window holds no bitmap", it) }
    }

    @Test
    fun `leaving the decode window releases the warm bitmap`() {
        companionAppIconCache.put("/data/leaving.png", icon)
        var decode by mutableStateOf(true)
        val seen = mutableListOf<CompanionDecodedArt?>()
        compose.setContent { seen += rememberCompanionIconArt("/data/leaving.png", decode, wired = { true }) }
        compose.waitForIdle()
        assertSame(icon, seen.last()?.bitmap)
        decode = false
        compose.waitForIdle()
        assertNull("outside the window the site holds nothing", seen.last())
    }

    // ---- the reveal: only a cold art arrival tweens; hiding snaps ------------------------------------------------------------

    @Test
    fun `only a cold art arrival fades in`() {
        assertTrue(companionArtRevealFades(visible = true, fadeIn = true, reduced = false))
        assertFalse("a warm arrival snaps", companionArtRevealFades(visible = true, fadeIn = false, reduced = false))
        assertFalse("reduced motion snaps", companionArtRevealFades(visible = true, fadeIn = true, reduced = true))
    }

    @Test
    fun `hiding always snaps, so a plate never lingers while a mounted site changes to a cold source`() {
        // The ground and the plate read the reveal; a tween toward zero would keep them on screen for 140 ms over a loading icon.
        listOf(true, false).forEach { fadeIn ->
            listOf(true, false).forEach { reduced ->
                assertFalse("fadeIn=$fadeIn reduced=$reduced", companionArtRevealFades(visible = false, fadeIn = fadeIn, reduced = reduced))
            }
        }
    }

    @Test
    fun `a site that draws nothing reads zero on its first frame, whatever alpha its animation still holds`() {
        // A site mounted ready and recycled onto a cold source composes NOTHING at once, while its animated alpha is still 1 for a frame.
        assertEquals(0f, companionArtRevealAlpha(CompanionArtDraw.NOTHING, animated = 1f), 0f)
        assertEquals(0f, companionArtRevealAlpha(CompanionArtDraw.NOTHING, animated = 0.6f), 0f)
        // Loaded art and the honest fallback follow the animation, so a cold fade and a snap are untouched.
        assertEquals(1f, companionArtRevealAlpha(CompanionArtDraw.ART, animated = 1f), 0f)
        assertEquals(0.4f, companionArtRevealAlpha(CompanionArtDraw.ART, animated = 0.4f), 0f)
        assertEquals(1f, companionArtRevealAlpha(CompanionArtDraw.FALLBACK, animated = 1f), 0f)
    }

    @Test
    fun `the guard is on the site's reveal, the value the ground, the plate and the art all read`() {
        val animated = androidx.compose.runtime.mutableStateOf(1f)
        val facts = companionArtReceiptFacts(CompanionArtLoad.LOADING, wired = true)
        val loading = CompanionArtSite(null, CompanionArtDraw.NOTHING, facts, animated)
        assertEquals("a loading site's ground never draws, though the animation still says 1", 0f, loading.reveal.value, 0f)
        val ready = CompanionArtSite(CompanionDecodedArt(null), CompanionArtDraw.ART, facts, animated)
        assertEquals("a drawing site reads the animation", 1f, ready.reveal.value, 0f)
        animated.value = 0.25f
        assertEquals("and follows it live", 0.25f, ready.reveal.value, 0f)
        assertEquals(0f, loading.reveal.value, 0f)
    }
}
