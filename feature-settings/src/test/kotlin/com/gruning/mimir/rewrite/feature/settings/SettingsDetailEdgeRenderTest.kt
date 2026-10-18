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
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirMarkBadge
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-09, the Settings pane's scroll edges, on the real [SettingsHome] in a stand-in for the app shell's frame (the header row and the footer legend as `AppShellScaffold` lays them) at
 * Main's 832.8 x 468.3 dp and 369 dpi, at font 1.0 and 1.3:
 *  - F5: with the last row of Settings > Music > Behaviour focused, the "If Android blocks it..." line under it stands whole above the footer (it was cut through by the footer at 1.3x);
 *  - F7: a focused row's halo ([MimirFocusTileRingReach]) is whole at the pane's top and bottom edges: the pane's scroll stops a ring's reach short of its clip, so no halo is cut flat;
 *  - F12: the Game music card's row reads "Let other music play over games" .
 * PNGs land in `build/test-captures/sweep28b/`; the pixels are the JVM's, a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDetailEdgeRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val rootDensity = 369f / 160f
    private var fontScale by mutableStateOf(1f)
    private var mounted = false

    private val music = MusicReadModel(
        musicEnabled = true,
        playing = true,
        nowPlaying = MusicNowPlayingReadModel("Tiny 079", "Music on this device", singleTrack = false, formatLine = "MP3 · 8 kbps · 8 kHz"),
        source = MusicSourceValue.CUSTOM,
        customSourceAvailable = true,
        order = MusicOrderValue.SHUFFLE,
        volumePercent = 50,
        songTitlesEnabled = true,
        folder = MusicFolderReadModel(friendlyPath = "SD › ROMs › music", trackCount = 62, lastScanLabel = null, scanning = false, error = null),
        launchSoundEnabled = true,
        launchSoundVolumePercent = 100,
        gameMusic = GameMusicDownloadReadModel.Unavailable,
        playOnHighlight = false,
        outputValue = "16-bit · 8 kHz",
        otherPlayersAccessGranted = false,
    )

    private fun mount() {
        if (mounted) return
        mounted = true
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(viewportWidthDp, viewportHeightDp),
            ) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(top = 20.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MimirMarkBadge(palette = palette, sizeDp = 34.dp)
                                    MimirChromeText("Settings", style = MimirTypeScale.TitleCaps, color = palette.textPrimary, maxLines = 1)
                                }
                                MimirChromeText("Home   Browse   Settings", style = MimirTypeScale.MenuAction, color = palette.textMuted, maxLines = 1)
                            }
                            Box(Modifier.fillMaxWidth().weight(1f).testTag(PaneTag)) {
                                SettingsHome(authority = authority, music = music)
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

    private fun settle(ms: Long = 1_200L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(fileName: String, dirName: String = "build/test-captures/sweep28b") {
        val dir = File(dirName).apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("EDGE wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /** A zoom on the focused row's lower edge and the note under it: [topDp] to [bottomDp] in the window, the full width, written to `build/test-captures/fix29b/`. */
    private fun saveZoom(fileName: String, topDp: Float, bottomDp: Float) {
        val shot = bitmap()
        val top = (topDp * rootDensity).toInt().coerceIn(0, shot.height - 1)
        val bottom = (bottomDp * rootDensity).toInt().coerceIn(top + 1, shot.height)
        val crop = Bitmap.createBitmap(shot, 0, top, shot.width, bottom - top)
        val dir = File("build/test-captures/fix29b").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("EDGE wrote ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /** The pane's scroll viewport: the detail pane's own box (4 dp under the shell's pane top) down to the footer. */
    private fun viewport(): Pair<Float, Float> {
        val pane = compose.onNodeWithTag(PaneTag, useUnmergedTree = true).getBoundsInRoot()
        return (pane.top.value + 4f) to pane.bottom.value
    }

    private fun named(text: String) = hasContentDescription(text) or hasText(text)

    private fun rows(title: String): SemanticsNodeInteractionCollection =
        compose.onAllNodes(hasClickAction() and hasAnyDescendant(named(title)), useUnmergedTree = true)

    private fun rowBounds(title: String): Pair<Float, Float>? {
        val nodes = rows(title).fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        val b = rows(title)[0].getUnclippedBoundsInRoot()
        return b.top.value to b.bottom.value
    }

    private fun openMusic() {
        mount()
        settle()
        compose.runOnIdle { authority.requestFocus(settingsRailFocusKey(SettingsCategory.MUSIC)) }
        settle()
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicPlayerEnabledRowId))
        }
        settle()
    }

    private fun press(action: ControllerAction) {
        compose.runOnIdle { dispatchToSettings(authority, action, onRailOpen = {}, onRowActivate = {}, onExit = {}) }
        settle(700L)
    }

    /** What a focused row leaves above and below it, inside the viewport, in dp (the ring's halo needs [MimirFocusTileRingReach] on each side). */
    private fun room(title: String): Pair<Float, Float> {
        val (top, bottom) = viewport()
        val row = rowBounds(title) ?: return Float.NaN to Float.NaN
        return (row.first - top) to (bottom - row.second)
    }

    private val pageTitles = listOf(
        "Music", "Source", "Order", "Volume", "Equalizer", "Behaviour", "Music folder", "Launch sound", "Let other music play over games", "Download game music",
    )

    /** The halo needs the ring's reach on each side of a focused row inside the viewport; the pane gives it that and [SettingsRevealOutset]'s air. 0.7 dp is the stand-in viewport's own rounding. */
    private fun assertHaloAir(title: String, tag: String) {
        val (above, below) = room(title)
        println("EDGE $tag $title: above=$above below=$below")
        val need = SettingsRevealOutset.value - 0.7f
        assertTrue("$tag $title: the halo's room above the row ($above dp) holds the ring's reach and its air ($need)", above >= need)
        assertTrue("$tag $title: the halo's room below the row ($below dp) holds the ring's reach and its air ($need)", below >= need)
    }

    private fun walkPage(scale: Float) {
        fontScale = scale
        openMusic()
        val tag = "font $scale"
        println("EDGE ---- $tag: ring reach ${MimirFocusTileRingReach.value} dp, reveal outset ${SettingsRevealOutset.value} dp; viewport ${viewport()}")
        pageTitles.forEachIndexed { i, t ->
            if (i > 0) press(ControllerAction.NAV_DOWN)
            assertHaloAir(t, "$tag down")
            if (t == "Equalizer" || t == "Music folder") save("f7-music-$scale-down-${t.replace(' ', '-')}.png")
            if (t == "Let other music play over games") save("f12-music-row-$scale.png")
        }
        pageTitles.reversed().forEachIndexed { i, t ->
            if (i > 0) press(ControllerAction.NAV_UP)
            assertHaloAir(t, "$tag up")
            if (t == "Equalizer" || t == "Music folder") save("f7-music-$scale-up-${t.replace(' ', '-')}.png")
        }
    }

    @Test
    fun `the Music page's halos keep their air at the pane's top and bottom edges at 1x`() = walkPage(1.0f)

    @Test
    fun `the Music page's halos keep their air at the pane's top and bottom edges at 1_3x`() = walkPage(1.3f)

    private fun behaviourEnd(scale: Float) {
        fontScale = scale
        openMusic()
        compose.runOnIdle {
            authority.requestFocus(detailKey(MusicBehaviourRowId))
            authority.rememberFocusForScope(MusicBehaviourScope, detailKey(MusicBehaviourRowIds.first()))
            authority.pushScope(MusicBehaviourScope)
        }
        settle()
        repeat(MusicBehaviourRowIds.size - 1) { press(ControllerAction.NAV_DOWN) }
        val last = "Show other players"
        assertTrue("focus is on the last switch", authority.state.value.focused == detailKey(MusicOtherPlayersRowId))
        val (top, bottom) = viewport()
        val hint = compose.onAllNodes(hasText(MusicOtherPlayersRestrictedHelp), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the help line is on the page", hint.isNotEmpty())
        val b = compose.onAllNodes(hasText(MusicOtherPlayersRestrictedHelp), useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        println("EDGE font $scale last row: hint ${b.top.value}..${b.bottom.value} dp, viewport $top..$bottom dp")
        save("f5-behaviour-end-$scale.png")
        // Sweep 10-09 (f5b): the focused row's halo reaches MimirFocusTileRingReach under the row; the note stands past it, so the halo does not lie over its first line.
        val row = rowBounds(last)!!
        val gap = b.top.value - row.second
        println("EDGE font $scale last row: row bottom ${row.second} dp, note top ${b.top.value} dp, gap $gap dp (ring reach ${MimirFocusTileRingReach.value})")
        assertTrue("font $scale: the note stands the ring's reach below the row ($gap >= ${MimirFocusTileRingReach.value})", gap >= MimirFocusTileRingReach.value - 0.7f)
        save("h4-behaviour-end-$scale.png", "build/test-captures/fix29b")
        saveZoom("h4-behaviour-note-zoom-$scale.png", row.second - 48f, b.bottom.value + 12f)
        assertTrue("font $scale: the help line stands whole above the footer (${b.bottom.value} <= $bottom), not cut through by it", b.bottom.value <= bottom - SettingsRevealNoteAir.value + 0.7f)
        assertTrue("font $scale: and below the pane's top", b.top.value >= top)
        assertHaloAir(last, "font $scale behaviour end")
    }

    @Test
    fun `the help under the last Behaviour switch stands whole above the footer at 1x`() = behaviourEnd(1.0f)

    @Test
    fun `the help under the last Behaviour switch stands whole above the footer at 1_3x`() = behaviourEnd(1.3f)

    private companion object {
        const val PaneTag = "shell-pane"
        const val FooterTag = "shell-footer"
    }
}
