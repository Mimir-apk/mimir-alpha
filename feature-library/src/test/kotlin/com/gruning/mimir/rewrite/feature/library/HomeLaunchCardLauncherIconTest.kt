package com.gruning.mimir.rewrite.feature.library

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit 2026-10-15, F7 resolution: the Android launch card drew the 192 px launcher-icon cache stretched to its 257 dp (593 px at Thor Main's density 369), about three times. It now renders the package's own
 * launcher icon at the px the card draws it at. This holds the three halves: the icon is recognised as the package's own cached icon and nothing else is replaced; it is drawn at the size asked, not scaled from a
 * smaller bitmap; and the real [HomeLaunchCard] at Thor Main asks for exactly the px its art box measures, at fonts 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardLauncherIconTest {

    @get:Rule
    val compose = createComposeRule()

    private val calendar = "com.google.android.calendar"
    private var fontScale by mutableStateOf(1f)
    private var shown by mutableStateOf<HomeLaunchCardUiState?>(null)

    @Before
    fun clean() = HomeLaunchCardIconCache.clear()

    @After
    fun cleanAfter() = HomeLaunchCardIconCache.clear()

    private fun installCalendar() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = PackageInfo().apply {
            packageName = calendar
            applicationInfo = ApplicationInfo().apply { packageName = calendar; name = calendar }
        }
        val pm = shadowOf(context.packageManager)
        pm.installPackage(info)
        // A white disc with a blue square: resolution-independent, so what is asked of it is what it draws.
        pm.setApplicationIcon(
            calendar,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.WHITE)
            },
        )
    }

    private fun cachedIconRef(pkg: String = calendar) = "/data/user/0/com.gruning.mimir.rewrite/cache/$NativeShelfIconCacheDirName/$pkg-com.x.Main-e1-abc.png"

    @Test
    fun `only the package's own cached launcher icon is recognised, never art the player chose`() {
        assertTrue(isNativeLauncherIconRef(cachedIconRef(), calendar))
        assertTrue("a file URI is the same file", isNativeLauncherIconRef("file://" + cachedIconRef(), calendar))
        assertFalse("another package's icon", isNativeLauncherIconRef(cachedIconRef("com.other.app"), calendar))
        assertFalse("a chosen icon in the app's media store", isNativeLauncherIconRef("/data/user/0/com.gruning.mimir.rewrite/files/appearance/icons/calendar.png", calendar))
        assertFalse("a hero or a cover", isNativeLauncherIconRef("/storage/emulated/0/ROMs/media_art/calendar.png", calendar))
        assertFalse("a content URI", isNativeLauncherIconRef("content://media/external/images/1", calendar))
        assertFalse(isNativeLauncherIconRef(null, calendar))
        assertFalse(isNativeLauncherIconRef("", calendar))
    }

    @Test
    fun `the icon is drawn at the size asked, a vector at 593 px as sharp as at 192`() {
        installCalendar()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        listOf(192, 593).forEach { px ->
            val bitmap = required("render at $px", renderLauncherIcon(context, calendar, px))
            assertEquals(px, bitmap.width)
            assertEquals(px, bitmap.height)
            val c = px / 2
            assertEquals("the disc's centre is opaque white at $px", AndroidColor.WHITE, bitmap.getPixel(c, c))
            // a legacy icon is centred on the plate at 72/108 of its edge (a disc of radius 1/3 px), so just inside THAT edge is still the disc, and the old full-bleed ring is bare.
            assertEquals("just inside the inset disc's edge is still the disc at $px", AndroidColor.WHITE, bitmap.getPixel(c, (px * 0.5f - px * 0.30f).toInt()))
            assertEquals("the old full-bleed edge is clear of the disc at $px", 0, bitmap.getPixel(c, (px * 0.04f).toInt() + 1) ushr 24)
            assertEquals("the corner is outside the disc, transparent, at $px", 0, bitmap.getPixel(1, 1) ushr 24)
        }
        assertNull("a package that is not installed renders nothing, and the cached file is painted", renderLauncherIcon(context, "com.not.installed", 593))
        assertEquals("never heavier than a cover", HomeLaunchCardIconMaxPx, renderLauncherIcon(context, calendar, 5_000)!!.width)
    }

    private fun <T : Any> required(message: String, value: T?): T {
        assertNotNull(message, value)
        return value!!
    }

    private val thorDensity = 369f / 160f
    private val thorWidthDp = 1920f / thorDensity
    private val thorHeightDp = 1080f / thorDensity

    private fun androidCard(artRef: String) = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.AndroidGame(AndroidPackageName(calendar)),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Calendar",
            artworkUri = artRef,
            tileIconUri = artRef,
            supportingLabel = "Android game",
        ),
        platformLabel = "Android",
    )

    private fun mount() {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = thorDensity, fontScale = fontScale),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredWidth(thorWidthDp.dp).requiredHeight(thorHeightDp.dp).testTag("launcher-icon-card")) {
                        shown?.let { state ->
                            HomeLaunchCard(state = state, onPlay = {}, onAchievements = {}, onOpenWith = {}, onClose = {}, authority = authority)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun artWidthPx(): Int =
        compose.onAllNodes(hasTestTag(HomeLaunchCardCoverArtTestTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.width.toInt()

    @Test
    fun `at Thor Main the card renders the icon at the px its art box measures, at fonts 1 and 1_3`() {
        installCalendar()
        mount()
        listOf(1.0f, 1.3f).forEach { font ->
            HomeLaunchCardIconCache.clear()
            fontScale = font
            shown = null
            compose.waitForIdle()
            shown = androidCard(cachedIconRef())
            compose.waitForIdle()
            val drawnPx = artWidthPx()
            val key = HomeLaunchCardIconCache.key(calendar, drawnPx)
            compose.waitUntil(5_000) { HomeLaunchCardIconCache.get(key) != null }
            val bitmap = HomeLaunchCardIconCache.get(key)!!
            println("LAUNCHER-ICON font=$font: art box $drawnPx px, icon rendered ${bitmap.width} x ${bitmap.height} px (the 192 px cache was stretched ${drawnPx / 192f}x)")
            assertEquals("font=$font: rendered at exactly the px the card draws it", drawnPx, bitmap.width)
            assertEquals(drawnPx, bitmap.height)
            assertTrue("font=$font: far past the 192 px cache the shelves keep (${bitmap.width})", bitmap.width > 192 * 2)
        }
    }

    @Test
    fun `a custom icon the player chose is left alone, nothing is rendered for it`() {
        installCalendar()
        mount()
        shown = androidCard("/data/user/0/com.gruning.mimir.rewrite/files/appearance/icons/my-calendar.png")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertNull("no icon was rendered for art that is not the package's own", HomeLaunchCardIconCache.get(HomeLaunchCardIconCache.key(calendar, artWidthPx())))
    }

    @Test
    fun `a card reopened for the same app lands on its icon at once, warm from the last few`() {
        installCalendar()
        mount()
        shown = androidCard(cachedIconRef())
        compose.waitForIdle()
        val key = HomeLaunchCardIconCache.key(calendar, artWidthPx())
        compose.waitUntil(5_000) { HomeLaunchCardIconCache.get(key) != null }
        val first = HomeLaunchCardIconCache.get(key)
        shown = null
        compose.waitForIdle()
        shown = androidCard(cachedIconRef())
        compose.waitForIdle()
        assertTrue("the same bitmap, not a second render", first === HomeLaunchCardIconCache.get(key))
    }
}
