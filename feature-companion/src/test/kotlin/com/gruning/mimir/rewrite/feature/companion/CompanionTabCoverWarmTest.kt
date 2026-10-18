package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Tabs drawer's game covers have a warm path (audit B-31/B-32, RC-37). The icon beside them was read from the shared cache on frame 0; a game's COVER was decoded again on every
 * open of the drawer, at the source's full size and uncached, and the tab drew nothing until it landed. It now lives in the same byte-budgeted cache as the icons (so a memory
 * trim that halves that cache halves these too), bounded to [CompanionTabCoverMaxPx] on its long edge, and a tab whose cover is warm paints it on the first composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionTabCoverWarmTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ref = "content://mimir.test/cover/1"

    @Before
    @After
    fun emptyTheCache() {
        companionAppIconCache.evictAll()
    }

    private fun cover() = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun mountDrawer(entry: CompanionAppEntry) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompanionRecentsDrawer(
                entries = listOf(entry),
                launchLocked = false,
                launchMessage = null,
                themeId = null,
                quickSlots = listOf(null, null, null, null, null),
                quickLaunchApps = emptyList(),
                onQuickLaunch = {},
                onQuickAssign = { _, _ -> },
                onLaunch = {},
                onClose = {},
                onDismiss = {},
            )
        }
    }

    private fun tabIcons() = compose.onAllNodesWithTag(CompanionTabIconTag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `a cover is cached in the icon cache under its own key and read back as a cover`() {
        assertNull(companionWarmTabCover(null))
        assertNull(companionWarmTabCover(ref))
        companionAppIconCache.put(companionTabCoverKey(ref), cover())
        val warm = companionWarmTabCover(ref)
        assertNotNull(warm)
        assertTrue("a cover, not an app icon: it keeps its own shape", warm!!.isCover)
        assertNull("another game's cover is not this one's", companionWarmTabCover("content://mimir.test/cover/2"))
        assertTrue("the key cannot meet an icon path", companionTabCoverKey("/data/icon.png") != "/data/icon.png")
    }

    @Test
    fun `the decode is bounded to a power-of-two sample that keeps the long edge at the cap or above`() {
        assertEquals(1, companionCoverSampleSize(400, 300))
        assertEquals(1, companionCoverSampleSize(1023, 600))
        assertEquals("1024 px halves once: 512 is the cap", 2, companionCoverSampleSize(1024, 700))
        assertEquals(4, companionCoverSampleSize(2048, 1500))
        assertEquals("never past the cap", 8, companionCoverSampleSize(4500, 3000))
        for (long in listOf(600, 1000, 2000, 3000, 5000)) {
            val sample = companionCoverSampleSize(long, long / 2)
            assertTrue("$long px at 1/$sample stays at the cap or above: ${long / sample}", long / sample >= CompanionTabCoverMaxPx)
            assertTrue("and under twice it: ${long / sample}", long / sample < 2 * CompanionTabCoverMaxPx)
        }
    }

    @Test
    fun `a tab whose cover is warm paints it on the very first composition`() {
        companionAppIconCache.put(companionTabCoverKey(ref), cover())
        mountDrawer(CompanionAppEntry(packageName = "org.example.emu", label = "Sonic", artRef = ref))
        assertEquals("the cached cover is drawn before any effect has run", 1, tabIcons())
    }

    @Test
    fun `control - a tab whose cover is not warm draws nothing on its first composition`() {
        mountDrawer(CompanionAppEntry(packageName = "org.example.emu", label = "Sonic", artRef = ref))
        assertEquals("nothing while it loads (R3/R8)", 0, tabIcons())
    }
}
