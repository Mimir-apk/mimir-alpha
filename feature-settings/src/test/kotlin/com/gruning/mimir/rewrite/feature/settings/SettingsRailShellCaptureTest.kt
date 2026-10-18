package com.gruning.mimir.rewrite.feature.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirMarkBadge
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Alpha-cert sweep 2026-10-09 (P2):** on Main the Settings rail's seventh entry stood against the footer, and with ABOUT focused the rail scrolled and PROFILE rose under the
 * ᛗ badge (`41b-logo.png`). Seven 48 dp entries and their gaps overflowed the pane Main gives them (about 367 dp between the shell's header and its footer) at the old 8 dp gap, so
 * the rail scrolled at rest by design ([SettingsRailEntryHeightDp]) and ABOUT was cut by the footer. the gap is 4 dp, the entry stays 48 dp and its type the
 * same, so all seven stand whole at rest; the rail still scrolls when ABOUT is focused (the ring's room), and what it must not do is draw outside that pane. The real
 * [SettingsHome] in a stand-in for the app shell's frame -- the
 * header row (the badge, the section name, the tabs) and the footer legend laid out as `AppShellScaffold` lays them, from the same core-ui parts -- at Main's 832.8 x 468.3 dp and
 * 369 dpi, at the font scales 1.0 and 1.3. PNGs land in `build/test-captures/sweepa/`; the pixels are the JVM's, a handheld's are device-owed.
 *
 * The law is read off the pixels and the bounds, not off the modifier chain: with ABOUT focused, nothing of the rail is drawn in the header's air between the badge and the
 * pane (compared with the same shell with an empty pane); every entry's ring, at its own focus, stands inside the pane (the first entry's up into that air, as it always has).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRailShellCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val rootDensity = 369f / 160f

    private var fontScale by mutableStateOf(1f)

    /** Every chrome line laid out under the shell: the spoken text and its layout. The node's own semantics hide the layout, so this is how a test asks a line whether it ellipsised. */
    private val laidOut = CopyOnWriteArrayList<Pair<String, TextLayoutResult>>()
    private var showSettings by mutableStateOf(true)
    private var mounted = false

    private fun mount() {
        if (mounted) return
        mounted = true
        // The window's own action bar would stand over the top 48 dp of the frame and cut the footer off the bottom: the shell has neither.
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(viewportWidthDp, viewportHeightDp),
                LocalMimirTextLayoutObserver provides { spoken, layout -> laidOut.add(spoken to layout) },
            ) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        Column(Modifier.fillMaxSize()) {
                            // The shell's top bar, as AppShellScaffold lays it on Main: 40 dp aside, 20 dp above and 8 dp below, the badge and the section name.
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 20.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MimirMarkBadge(palette = palette, sizeDp = 34.dp, modifier = Modifier.testTag(BadgeTag))
                                    MimirChromeText("Settings", style = MimirTypeScale.TitleCaps, color = palette.textPrimary, maxLines = 1)
                                }
                                MimirChromeText("Home   Browse   Settings", style = MimirTypeScale.MenuAction, color = palette.textMuted, maxLines = 1)
                            }
                            Box(Modifier.fillMaxWidth().weight(1f).testTag(PaneTag)) {
                                if (showSettings) SettingsHome(authority = authority)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag(FooterTag).padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf("A" to "Select", "B" to "Back", "L2/R2" to "Tabs").forEachIndexed { index, (key, label) ->
                                    if (index > 0) Spacer(Modifier.width(24.dp))
                                    MimirFooterKeycap(key = key, palette = palette, modifier = Modifier.padding(end = 6.dp))
                                    MimirChromeText(label, style = MimirTypeScale.ControlHint, color = palette.textMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun settle(ms: Long = 1_500L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun focus(category: SettingsCategory) {
        compose.runOnIdle { authority.requestFocus(settingsRailFocusKey(category)) }
        settle(2_000L)
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, fileName: String) {
        val dir = File("build/test-captures/sweepa").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RAILSHELL wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun entryBoundsDp(category: SettingsCategory) =
        compose.onNodeWithTag(SettingsRailEntryTestTagPrefix + category.name, useUnmergedTree = true).getBoundsInRoot()

    private data class Band(val topDp: Float, val bottomDp: Float)

    private fun pane() = compose.onNodeWithTag(PaneTag, useUnmergedTree = true).getBoundsInRoot().let { Band(it.top.value, it.bottom.value) }
    private fun badgeBottomDp() = compose.onNodeWithTag(BadgeTag, useUnmergedTree = true).getBoundsInRoot().bottom.value
    private fun footerTopDp() = compose.onNodeWithTag(FooterTag, useUnmergedTree = true).getBoundsInRoot().top.value

    /**
     * The rail at rest, Profile focused: all seven entries stand whole inside the pane (the rail's viewport), 48 dp high and 4 dp apart, and no entry's name is ellipsised.
     * Policy 10-09 (the gap tightened from 8 dp so ABOUT is no longer cut by the footer; "APPEARANCE" must never be "APPEARAN...").
     */
    private fun assertAtRest(pane: Band, tag: String) {
        var previousBottom = Float.NaN
        for (category in SettingsCategory.rail) {
            val entry = entryBoundsDp(category)
            val top = entry.top.value
            val bottom = entry.bottom.value
            println("RAILSHELL $tag rest ${category.name} entry=$top..$bottom pane=${pane.topDp}..${pane.bottomDp}")
            assertTrue("$tag at rest ${category.name}: its top ($top) is inside the pane (${pane.topDp})", top >= pane.topDp - 0.01f)
            assertTrue("$tag at rest ${category.name}: its bottom ($bottom) is inside the pane (${pane.bottomDp}), not cut by the footer", bottom <= pane.bottomDp + 0.01f)
            assertEquals("$tag at rest ${category.name}: the entry keeps its 48 dp", 48f, bottom - top, 0.3f)
            if (!previousBottom.isNaN()) assertEquals("$tag at rest ${category.name}: 4 dp under the entry above", 4f, top - previousBottom, 0.3f)
            previousBottom = bottom
        }
        val ringTop = entryBoundsDp(SettingsCategory.PROFILE).top.value - MimirFocusTileRingReach.value
        assertTrue("$tag at rest PROFILE: the ring's top ($ringTop) is clear of the badge's lower edge (${badgeBottomDp()})", ringTop >= badgeBottomDp() - 0.01f)

        for (category in SettingsCategory.rail) {
            val lines = laidOut.filter { it.first == category.label }
            assertTrue("$tag: the entry ${category.name} ('${category.label}') was laid out", lines.isNotEmpty())
            for ((spoken, layout) in lines) {
                assertEquals("$tag: '$spoken' is one line", 1, layout.lineCount)
                assertFalse("$tag: '$spoken' is not ellipsised", (0 until layout.lineCount).any(layout::isLineEllipsized))
                assertFalse("$tag: '$spoken' does not overflow its width", layout.didOverflowWidth)
            }
        }
    }

    private fun runScale(scale: Float, tag: String) {
        fontScale = scale
        mount()
        settle()
        val pane = pane()
        println("RAILSHELL $tag pane=${pane.topDp}..${pane.bottomDp} dp (height ${pane.bottomDp - pane.topDp}), badge bottom=${badgeBottomDp()}, footer top=${footerTopDp()}")
        assertEquals("the pane ends where the footer begins", pane.bottomDp, footerTopDp(), 0.6f)
        assertTrue("the pane begins below the badge", pane.topDp >= badgeBottomDp() - 0.01f)

        assertAtRest(pane, tag)

        // At rest, Profile focused, and the rest with the pane empty: the baseline for "nothing of the rail is drawn in the header's air".
        save(bitmap(), "settings-rail-rest-$tag.png")
        compose.runOnIdle { showSettings = false }
        settle()
        val emptyPane = bitmap()
        save(emptyPane, "settings-pane-empty-$tag.png")
        compose.runOnIdle { showSettings = true }
        settle()

        focus(SettingsCategory.ABOUT)
        val about = bitmap()
        save(about, "settings-rail-about-$tag.png")

        // Nothing of the rail stands in the header's air once it has scrolled: the band between the badge and the pane, across the rail's columns, equals the empty pane's.
        val px = rootDensity
        val top = (badgeBottomDp() * px).toInt()
        val bottom = (pane.topDp * px).toInt()
        val left = 0
        val right = (viewportWidthDp * 0.30f * px).toInt()
        var differing = 0
        for (y in top until bottom) for (x in left until right) if (about.getPixel(x, y) != emptyPane.getPixel(x, y)) differing++
        println("RAILSHELL $tag header-air band y=$top..$bottom x=$left..$right differing pixels with ABOUT focused: $differing")
        assertEquals("$tag: with ABOUT focused the rail draws nothing in the header's air", 0, differing)

        // Every entry at its own focus: its ring's reach stands inside the pane (the first entry's up into the header's air, which is the one place the ring may go).
        for (category in SettingsCategory.rail) {
            focus(category)
            val entry = entryBoundsDp(category)
            val ringTop = entry.top.value - MimirFocusTileRingReach.value
            val ringBottom = entry.bottom.value + MimirFocusTileRingReach.value
            println("RAILSHELL $tag ${category.name} entry=${entry.top.value}..${entry.bottom.value} ring=$ringTop..$ringBottom pane=${pane.topDp}..${pane.bottomDp}")
            if (category == SettingsCategory.PROFILE) {
                // The first entry's ring reaches up into the header's air and no further: it ends at the badge's lower edge, never inside the badge's band (it had ended 2.3 dp inside).
                assertTrue("$tag PROFILE: the ring's top ($ringTop) stays clear of the badge's lower edge (${badgeBottomDp()})", ringTop >= badgeBottomDp() - 0.01f)
            } else {
                assertTrue("$tag ${category.name}: the ring's top ($ringTop) is inside the pane (${pane.topDp})", ringTop >= pane.topDp - 0.5f)
            }
            assertTrue("$tag ${category.name}: the ring's bottom ($ringBottom) is above the footer (${pane.bottomDp})", ringBottom <= pane.bottomDp + 0.5f)
            if (category == SettingsCategory.CONTROLS || category == SettingsCategory.LIBRARY) save(bitmap(), "settings-rail-${category.name.lowercase()}-$tag.png")
        }
    }

    @Test
    fun `the rail at 1x draws nothing in the header's air once scrolled, and every ring stands in the pane`() = runScale(1.0f, "1.0x")

    @Test
    fun `the rail at 1_3x draws nothing in the header's air once scrolled, and every ring stands in the pane`() = runScale(1.3f, "1.3x")

    private companion object {
        const val BadgeTag = "shell-badge"
        const val PaneTag = "shell-pane"
        const val FooterTag = "shell-footer"
    }
}
