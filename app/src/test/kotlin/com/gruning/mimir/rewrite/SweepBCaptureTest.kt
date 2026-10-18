package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardKeyIds
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardPanelTag
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementsKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardRenameTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardTitleEditKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardTitleTestTag
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.MimirLaunchCardExtraAction
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay
import com.gruning.mimir.rewrite.feature.settings.SettingsHome
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.text.TextLayoutResult

/**
 * Emulator render coverage for visual geometry on Main: 1920 x 1080 px at 369 dpi (832.5 x 468.3 dp, density 2.30625),
 * at font 1.0 and 1.3, API 30 (the level at which the platform cannot blur), native graphics so text shapes and wraps. The PNGs land in `app/build/sweepb/` and `build/test-captures/sweepb/` when `build/test-captures`
 * exists. Pixel values are the JVM's; the handheld's are device-owed.
 *
 *  - the launch card's button row: a focused chip's halo (its plate grown by the ring's whole reach) clears every neighbouring plate, and the Rename caption stands a ring's reach off the glyph;
 *  - the Get-art connect prompt: "Not now" and "Connect" the same;
 *  - the Open with sheet: one edge, not an outer border and an inner hairline with a gap between them;
 *  - the Settings page: no stray crest under the status pill;
 *  - the keyboard: the Done key is the warm etched primary (at rest and focused), and the panel is opaque over a busy backdrop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SweepBCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val mainDensity = 2.30625f
    private val mainWidthDp = 832.5f
    private val mainHeightDp = 468.3f
    private val observedLayouts = mutableMapOf<String, TextLayoutResult>()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /**
     * The ring's reach as layout reserves it: the token in WHOLE pixels (a gap is laid out in pixels). At Main's 2.30625 the 14 dp token is 32.29 px and a gap of it is 32; the
     * token is the ring's real 13.5 dp reach rounded UP (31.14 px here), so the half dp it carries is what the rounding spends and the drawn ring still clears.
     */
    private fun reachPx(): Float = with(Density(density = mainDensity)) { MimirFocusTileRingReach.roundToPx().toFloat() }

    private fun dpPx(dp: Dp): Float = with(Density(density = mainDensity)) { dp.toPx() }

    private fun mount(fontScale: Float, widthDp: Float = mainWidthDp, heightDp: Float = mainHeightDp, content: @androidx.compose.runtime.Composable () -> Unit) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        observedLayouts.clear()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = mainDensity, fontScale = fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(mainWidthDp, mainHeightDp),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observedLayouts[spoken] = layout },
            ) {
                MimirTheme { Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { content() } }
            }
        }
        settle()
    }

    private fun writePng(fileName: String, crop: Rect? = null) {
        val view = compose.activity.window.decorView
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(whole))
        val bitmap = if (crop == null) {
            whole
        } else {
            val left = crop.left.toInt().coerceIn(0, whole.width - 1)
            val top = crop.top.toInt().coerceIn(0, whole.height - 1)
            Bitmap.createBitmap(whole, left, top, (crop.right.toInt().coerceAtMost(whole.width) - left).coerceAtLeast(1), (crop.bottom.toInt().coerceAtMost(whole.height) - top).coerceAtLeast(1))
        }
        val out = File(File("build/sweepb").apply { mkdirs() }, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "sweepb/$fileName").apply { parentFile.mkdirs() }, overwrite = true)
        println("SWEEPB wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun boundsOf(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun union(vararg rects: Rect): Rect =
        rects.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

    // ---- 1. the launch card: the button row, the Rename caption, UP to the rename mark ----

    private val favoriteKey = FocusKey("library/detail/shelf")

    private val cardState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("snes/super-metroid")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Super Metroid",
            supportingLabel = "snes",
        ),
        platformLabel = "Super Nintendo",
        emulatorLabel = "Automatic",
        lastPlayedLabel = "Never",
    )

    private fun launchCard(fontScale: Float, authority: DefaultFocusAuthority) {
        mount(fontScale) {
            HomeLaunchCard(
                state = cardState,
                onPlay = {},
                onAchievements = {},
                onOpenWith = {},
                onClose = {},
                authority = authority,
                extraAction = MimirLaunchCardExtraAction(label = "Favorite", key = favoriteKey, onActivate = {}),
            )
        }
    }

    private fun launchCardHalosClear(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }
        launchCard(fontScale, authority)
        val keys = listOf(
            "play" to HomeLaunchCardPlayKey,
            "favorite" to favoriteKey,
            "open-with" to HomeLaunchCardOpenWithKey,
            "achievements" to HomeLaunchCardAchievementsKey,
        )
        val plates = keys.associate { (name, key) -> name to boundsOf(homeLaunchCardActionTestTag(key)) }
        val reach = reachPx()
        val row = union(*plates.values.toTypedArray()).inflate(reach + dpPx(6.dp))
        for ((name, key) in keys) {
            compose.runOnIdle { authority.requestFocus(key) }
            settle()
            val halo = plates.getValue(name).inflate(reach)
            for ((other, plate) in plates) {
                if (other == name) continue
                assertTrue(
                    "at ${fontScale}x the focused $name's halo reaches the $other plate: halo=$halo plate=$plate (the gap is ${plate.left - plates.getValue(name).right} px, a ring reach is $reach px)",
                    !halo.overlaps(plate),
                )
            }
            writePng("launchcard-row-$name-focused$suffix.png", crop = row)
        }
    }

    @Test
    fun `launch card - at 1x a focused button's halo clears every other button`() = launchCardHalosClear(1f, "-1.0x")

    @Test
    fun `launch card - at 1_3x a focused button's halo clears every other button`() = launchCardHalosClear(1.3f, "-1.3x")

    private fun renameCaptionClearsTheHalo(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) }
        launchCard(fontScale, authority)
        compose.runOnIdle { authority.requestFocus(HomeLaunchCardTitleEditKey) }
        settle()
        val glyph = boundsOf(HomeLaunchCardRenameTestTag)
        val title = boundsOf(HomeLaunchCardTitleTestTag)
        // The caption speaks its natural word as a content description (MimirChromeText replaces the all-caps text's semantics); the glyph speaks the same word, so the caption is
        // the one that stands to the glyph's right.
        val caption = compose.onAllNodes(hasContentDescription("Rename"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.left >= glyph.right - 1f }.minByOrNull { it.left }
            ?: error("the Rename caption is not up while the mark is focused")
        val reach = reachPx()
        val halo = glyph.inflate(reach)
        assertTrue("at ${fontScale}x the Rename halo covers the caption: halo=$halo caption=$caption (glyph.right=${glyph.right})", caption.left >= halo.right - 1.5f)
        assertTrue("and clears the name: halo=$halo title=$title", halo.left >= title.right - 1.5f)
        writePng("launchcard-rename-focused$suffix.png", crop = union(title, glyph, caption).inflate(reach + dpPx(8.dp)))
    }

    @Test
    fun `launch card - at 1x the focused Rename mark's halo clears the name and its caption`() = renameCaptionClearsTheHalo(1f, "-1.0x")

    @Test
    fun `launch card - at 1_3x the focused Rename mark's halo clears the name and its caption`() = renameCaptionClearsTheHalo(1.3f, "-1.3x")

    // ---- 2. the Get-art connect prompt: Not now and Connect ----

    private fun connectPromptHalosClear(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        mount(fontScale) {
            GetArtOverlay(
                state = GetArtUi.NeedsAccount,
                provider = GetArtProvider.SCREENSCRAPER,
                onConnect = {},
                onAddSteamGridDbKey = {},
                onDismiss = {},
                authority = authority,
            )
        }
        settle()
        val notNow = boundsOf(etchedChipPlateTag("get-art", "Not now"))
        val connect = boundsOf(etchedChipPlateTag("get-art", "Connect"))
        val reach = reachPx()
        assertTrue("at ${fontScale}x the focused Not now's halo reaches Connect: halo=${notNow.inflate(reach)} connect=$connect", !notNow.inflate(reach).overlaps(connect))
        val both = union(notNow, connect).inflate(reach + dpPx(8.dp))
        writePng("connect-prompt-not-now-focused$suffix.png", crop = both)
        compose.runOnIdle { authority.requestFocus(GetArtConnectKey) }
        settle()
        assertTrue("at ${fontScale}x the focused Connect's halo reaches Not now: halo=${connect.inflate(reach)} not-now=$notNow", !connect.inflate(reach).overlaps(notNow))
        writePng("connect-prompt-connect-focused$suffix.png", crop = both)
        writePng("connect-prompt-whole$suffix.png")
    }

    @Test
    fun `connect prompt - at 1x a focused chip's halo clears the other`() = connectPromptHalosClear(1f, "-1.0x")

    @Test
    fun `connect prompt - at 1_3x a focused chip's halo clears the other`() = connectPromptHalosClear(1.3f, "-1.3x")

    // ---- 3. the Open with sheet: one edge ----

    private fun openWithSheet(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        val scope = FocusScope("sweepb/open-with")
        authority.pushScope(scope)
        val items = listOf<MimirSelectItem>(
            MimirSelectItem.Option(FocusKey("sweepb/ow/auto"), label = "Automatic", current = true),
            MimirSelectItem.Option(FocusKey("sweepb/ow/1"), label = "RetroArch 64 / Snes9x", subtitle = "Not installed", enabled = false),
            MimirSelectItem.Option(FocusKey("sweepb/ow/2"), label = "RetroArch 64 / Snes9x 2010", subtitle = "Not installed", enabled = false),
            MimirSelectItem.Option(FocusKey("sweepb/ow/3"), label = "RetroArch 64 / Snes9x2005", subtitle = "Not installed", enabled = false),
            MimirSelectItem.Option(FocusKey("sweepb/ow/4"), label = "RetroArch 64 / Bsnes", subtitle = "Not installed", enabled = false),
        )
        mount(fontScale) {
            MimirSelectOverlay(
                authority = authority,
                scope = scope,
                title = "Open with",
                items = items,
                onSelect = {},
                onDismiss = {},
                presenceManagedExternally = true,
            )
        }
        settle()
        val options = boundsOf(com.gruning.mimir.rewrite.core.ui.select.MimirSelectOptionsTestTag)
        writePng("open-with-sheet$suffix.png")
        // The footer legend sits wholly inside the panel (it was cut in half at the panel's bottom edge at 1.3x, five rows being taller than the panel), under the list and
        // clear of it, and it keeps its full height: the keycap is a 24 dp plate.
        val panel = boundsOf(com.gruning.mimir.rewrite.core.ui.select.MimirSelectPanelTestTag)
        val footer = boundsOf(com.gruning.mimir.rewrite.core.ui.select.MimirSelectFooterTestTag)
        println("SWEEPB open-with$suffix: panel=$panel options=$options footer=$footer")
        assertTrue("at ${fontScale}x the footer is inside the panel's sides: footer=$footer panel=$panel", footer.left >= panel.left && footer.right <= panel.right)
        assertTrue("at ${fontScale}x the footer's bottom is inside the panel, clear of its padding: footer=$footer panel=$panel", footer.bottom <= panel.bottom - dpPx(com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing.lg) + 1f)
        assertTrue("at ${fontScale}x the footer stands under the list and not over it: footer=$footer options=$options", footer.top >= options.bottom - 1f)
        assertTrue("at ${fontScale}x the footer keeps its full height (a 24 dp keycap), not a clipped sliver: ${footer.height}", footer.height >= dpPx(24.dp) - 1f)
        // The edge: a row of pixels through the panel's vertical middle, from the scrim into the panel's body, 16 dp either side of where the panel's left border stands.
        val view = compose.activity.window.decorView
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(whole))
        val y = (options.top + options.height / 2f).toInt()
        val panelLeft = (options.left - dpPx(16.dp) - dpPx(2.dp)).toInt()
        val start = (panelLeft - dpPx(10.dp)).toInt()
        val end = (options.left - dpPx(2.dp)).toInt()
        fun luma(x: Int): Int {
            val p = whole.getPixel(x, y)
            return ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
        }
        val profile = (start..end).map(::luma)
        println("SWEEPB open-with edge profile$suffix: y=$y x=$start..$end ${profile.joinToString(",")}")
        // Bands: runs of pixels brighter than the quiet ground between them by at least 6 levels. A single border is one band; an inset hairline beside it is two.
        val ground = profile.sorted()[profile.size / 4]
        val bands = run {
            var count = 0
            var inBand = false
            for (v in profile) {
                val lit = v >= ground + 6
                if (lit && !inBand) count += 1
                inBand = lit
            }
            count
        }
        assertEquals("at ${fontScale}x the sheet has ONE edge here, not an outer border and an inner outline: ${profile.joinToString(",")}", 1, bands)
    }

    @Test
    fun `open with sheet - at 1x the panel has one edge`() = openWithSheet(1f, "-1.0x")

    @Test
    fun `open with sheet - at 1_3x the panel has one edge`() = openWithSheet(1.3f, "-1.3x")

    // ---- 4. Settings: no stray crest under the status pill ----

    private fun settingsHome(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        mount(fontScale) { SettingsHome(authority = authority) }
        settle()
        writePng("settings-home$suffix.png")
        val topRight = Rect(mainWidthDp * mainDensity * 0.80f, 0f, mainWidthDp * mainDensity, mainHeightDp * mainDensity * 0.30f)
        writePng("settings-home-top-right$suffix.png", crop = topRight)
    }

    @Test
    fun `settings home - at 1x there is no crest in the top right`() = settingsHome(1f, "-1.0x")

    @Test
    fun `settings home - at 1_3x there is no crest in the top right`() = settingsHome(1.3f, "-1.3x")

    // ---- 5. the keyboard: the Done key is the warm etched primary, the panel is opaque ----

    private fun keyboardOverBusyBackdrop(fontScale: Float, suffix: String, fill: Boolean) {
        val authority = DefaultFocusAuthority()
        val widthDp = if (fill) 537.7f else mainWidthDp
        val value = mutableStateOf("chrome")
        mount(fontScale, widthDp = widthDp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1E2A44))) {
                // A busy backdrop: the host app's suggestion rows, bright text and bands that would read through a see-through panel.
                Column(Modifier.fillMaxSize()) {
                    repeat(14) { row ->
                        Row(Modifier.fillMaxWidth().height(34.dp).background(if (row % 2 == 0) Color(0xFFF2F2F2) else Color(0xFF0A7BFF)), verticalAlignment = Alignment.CenterVertically) {
                            Text("chrome://suggestion/$row  https://example.com/a/very/long/suggestion/path/$row", color = if (row % 2 == 0) Color.Black else Color.White, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                val h = rememberMimirKeyboardHost(authority, true)
                Box(Modifier.align(if (fill) Alignment.BottomCenter else Alignment.Center).width(widthDp.dp)) {
                    MimirKeyboard(
                        value = value.value,
                        onValueChange = { value.value = it },
                        onDone = {},
                        controller = h?.controller,
                        keyDecorator = h?.keyDecorator,
                        targetLabel = "Search or type URL",
                        floating = !fill,
                        fill = fill,
                    )
                }
            }
        }
        settle()
        val name = if (fill) "keyboard-bottom" else "keyboard-main"
        // The keyboard enters on Done (a field that has text), so the first picture would already carry the ring. At rest the Done key is NOT the focused one: the pad sits on "1".
        compose.runOnIdle { authority.requestFocus(mimirKeyboardFocusKey(MimirKeyboardKeyIds.character("1").value)) }
        settle()
        writePng("$name-done-rest$suffix.png")
        val restPicture = Bitmap.createBitmap(compose.activity.window.decorView.width, compose.activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            .also { compose.activity.window.decorView.draw(Canvas(it)) }
        compose.runOnIdle { authority.requestFocus(mimirKeyboardFocusKey(MimirKeyboardKeyIds.Done.value)) }
        settle()
        writePng("$name-done-focused$suffix.png")
        val focusedPicture = Bitmap.createBitmap(compose.activity.window.decorView.width, compose.activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            .also { compose.activity.window.decorView.draw(Canvas(it)) }
        var changed = 0
        for (x in 0 until restPicture.width step 2) for (y in 0 until restPicture.height step 2) if (restPicture.getPixel(x, y) != focusedPicture.getPixel(x, y)) changed += 1
        assertTrue("at ${fontScale}x focusing the Done key changed the picture (the ring on its plate), $changed sampled pixels differ", changed > 300)
        val panel = boundsOf(MimirKeyboardPanelTag)
        // Opaque: a pixel in the gap between two keys, inside the panel, is the panel's ground and not the busy rows behind it. The busy rows are black-on-white or white-on-blue
        // text over F2F2F2 and 0A7BFF bands; the panel's ground is the theme's dark surface.
        val view = compose.activity.window.decorView
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(whole))
        val sampleX = (panel.left + dpPx(6.dp)).toInt()
        val sampleYs = listOf(0.3f, 0.5f, 0.7f, 0.9f).map { (panel.top + panel.height * it).toInt() }
        for (sy in sampleYs) {
            val p = whole.getPixel(sampleX, sy)
            val luma = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
            assertTrue("at ${fontScale}x the keyboard panel shows the page behind it at ($sampleX,$sy): luma $luma", luma < 90)
        }
    }

    @Test
    fun `keyboard - the Done key is the warm etched primary and the panel is opaque over a busy backdrop at 1x`() =
        keyboardOverBusyBackdrop(1f, "-1.0x", fill = false)

    @Test
    fun `keyboard - the Done key is the warm etched primary and the panel is opaque over a busy backdrop at 1_3x`() =
        keyboardOverBusyBackdrop(1.3f, "-1.3x", fill = false)

    @Test
    fun `keyboard on the Bottom - the Done key is the warm etched primary and the panel is opaque at 1x`() =
        keyboardOverBusyBackdrop(1f, "-1.0x", fill = true)
}
