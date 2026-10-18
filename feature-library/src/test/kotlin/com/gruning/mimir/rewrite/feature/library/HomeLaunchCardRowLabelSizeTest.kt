package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
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

/**
 * at font 1.3 the launch card's buttons wore two label sizes, because only "ACHIEVEMENTS" stepped down from 12 sp and its neighbours stayed at 12 sp.
 * To keep label sizes even, the size is solved once (the smallest any label needs) and every button wears it: a row's buttons by
 * [homeLaunchActionRowLabelStyle], and, in the stacked shape, both rows together (one size for the whole footer).
 *
 * Two layers. The MEASURE half drives [homeLaunchActionRowLabelStyle] with a fake measure (a label is as wide as it has letters), so the rule is pinned
 * with no renderer: one style per call, the smallest any label needs, the base when all fit. The RENDER half draws the real [HomeLaunchCard], reads each
 * button's laid-out label size from the text layout the card's labels report, and asserts one size across every button on the card at fonts 1.0 and 1.3, on
 * Thor Main's one row and on the two stacked rows. It also saves the card as a PNG under this module's `build/reports` (the paths are printed). Pixels are the JVM's; the
 * handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardRowLabelSizeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var fontScale by mutableStateOf(1f)

    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    // ---- the measure half ---------------------------------------------------------------------------------------------------

    private val base: TextStyle = TextStyle(fontSize = 12.sp)

    /** A label is `letters x fontSize` wide: a longer word needs a smaller size to fit the same room. */
    private val measure: (String, TextStyle) -> Int = { text, style -> Math.round(text.length * style.fontSize.value) }

    @Test
    fun `a row is drawn in the smallest size any of its labels needs, and every label in it gets that one`() {
        // PLAY is 4 letters, ACHIEVEMENTS 12: at 12 sp the long one needs 144 px and the room is 120, so it steps down; the short ones fit at 12 sp.
        val labels = listOf("Play" to 120, "Open with" to 120, "Achievements" to 120)
        val row = homeLaunchActionRowLabelStyle(labels, base, measure)

        val alone = labels.map { (label, room) -> label to homeLaunchActionLabelStyle(label, room, base, measure).fontSize.value }
        assertEquals("the neighbours fit alone at the base size", 12f, alone[0].second, 0f)
        assertEquals(12f, alone[1].second, 0f)
        assertTrue("the long label alone is smaller: ${alone[2].second}", alone[2].second < 12f)
        assertEquals("the row wears what the long one needs", alone[2].second, row.fontSize.value, 0f)
        assertTrue("and that is below the base", row.fontSize.value < 12f)
    }

    @Test
    fun `a row whose labels all fit keeps the base size, and an empty row has nothing to size`() {
        val fits = homeLaunchActionRowLabelStyle(listOf("Play" to 120, "Open with" to 120), base, measure)
        assertEquals(12f, fits.fontSize.value, 0f)
        assertEquals(base, homeLaunchActionRowLabelStyle(emptyList(), base, measure))
    }

    @Test
    fun `the room each button of a row really gets is what a Row leaves it`() {
        val nominal = listOf(96.dp, 136.dp, 120.dp, 136.dp)
        assertEquals("a row that wraps its content gives every button its width", nominal, homeLaunchRowSeatWidths(nominal, androidx.compose.ui.unit.Dp.Infinity))
        // A 200 dp row: 96, then 200 - 96 - 14 (the gap, one ring reach) = 90 left for the second, then nothing is left (a negative room is zero).
        val squeezed = homeLaunchRowSeatWidths(nominal, 200.dp)
        assertEquals(96.dp, squeezed[0])
        assertEquals(200.dp - 96.dp - HomeLaunchCardActionGap, squeezed[1])
        assertEquals(90.dp, squeezed[1])
        assertEquals(0.dp, squeezed[2])
        assertEquals(0.dp, squeezed[3])
    }

    // ---- the render half ----------------------------------------------------------------------------------------------------

    private val cardState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Super Mario 64",
            supportingLabel = "n64",
        ),
        platformLabel = "Nintendo 64",
        emulatorLabel = "RetroArch",
        lastPlayedLabel = "Yesterday",
    )

    private val parkedKeys = listOf(HomeLaunchCardPlayKey, HomeLaunchCardRestartKey, HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)
    private val unparkedKeys = listOf(HomeLaunchCardPlayKey, HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)

    private val layouts = mutableMapOf<String, TextLayoutResult>()
    private val cardTag = "row-label-size-card"

    private fun labelOf(key: FocusKey): String = when (key) {
        HomeLaunchCardPlayKey -> cardState.primaryActionLabel
        HomeLaunchCardRestartKey -> HomeLaunchCardResume.RestartLabel
        HomeLaunchCardOpenWithKey -> cardState.openWithActionLabel
        else -> "Achievements"
    }

    private fun mount(widthDp: Float, heightDp: Float, posture: MimirViewportPosture, parked: Boolean) {
        HomeLaunchCardResume.set(if (parked) GameId("game-1") else null)
        layouts.clear()
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides MimirViewportProfile(posture, widthDp, heightDp),
                LocalMimirTextLayoutObserver provides { spoken: String, layout: TextLayoutResult -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(cardTag)) {
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(key: FocusKey): Rect? =
        compose.onAllNodes(hasTestTag(homeLaunchCardActionTestTag(key)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** The size each button's label was laid out at, in sp: what the player sees, as the text layout holds it. */
    private fun sizeOf(key: FocusKey): Float {
        val layout = requireNotNull(layouts[labelOf(key)]) { "the ${labelOf(key)} label was not laid out" }
        return layout.layoutInput.style.fontSize.value
    }

    /** The buttons grouped into visual rows (the same top, within a pixel), each as the sizes its labels were drawn at. */
    private fun rowsOf(keys: List<FocusKey>): List<List<Pair<FocusKey, Float>>> {
        val rows = mutableListOf<MutableList<Triple<FocusKey, Float, Float>>>()
        keys.forEach { key ->
            val top = requireNotNull(bounds(key)) { "$key is not on the card" }.top
            val size = sizeOf(key)
            val row = rows.firstOrNull { kotlin.math.abs(it.first().third - top) < 1f }
            if (row != null) row += Triple(key, size, top) else rows += mutableListOf(Triple(key, size, top))
        }
        return rows.map { row -> row.map { it.first to it.second } }
    }

    /** Every button on the card, whichever row it is on, was laid out at one size; returns it. */
    private fun assertOneSizeOnTheCard(name: String, keys: List<FocusKey>, expectedRows: Int): Float {
        val rows = rowsOf(keys)
        println("ROW-LABEL-SIZE $name rows=" + rows.joinToString(" | ") { row -> row.joinToString { (key, size) -> "${key.value.substringAfterLast('/')}=$size" } })
        assertEquals("$name: the buttons sit in $expectedRows row(s)", expectedRows, rows.size)
        val sizes = rows.flatten().map { it.second }.distinct()
        assertEquals("$name: every button on the card wears one label size, not $sizes", 1, sizes.size)
        return sizes.single()
    }

    private fun savePng(name: String) {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val card = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val left = Math.round(card.left).coerceIn(0, full.width - 1)
        val top = Math.round(card.top).coerceIn(0, full.height - 1)
        val crop = Bitmap.createBitmap(full, left, top, minOf(Math.round(card.width), full.width - left), minOf(Math.round(card.height), full.height - top))
        val out = File(File(System.getProperty("user.dir"), "build/reports"), "$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("ROW-LABEL-SIZE-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }

    @Test
    fun `at Thor Main every button wears one label size at fonts 1 and 1_3`() {
        mount(832.5f, 468.3f, MimirViewportPosture.STANDARD, parked = true)

        fontScale = 1.0f
        compose.waitForIdle()
        val atOne = assertOneSizeOnTheCard("thor-main font=1.0", parkedKeys, expectedRows = 1)
        assertEquals("at font 1.0 nothing steps down: the chip's own 12 sp", 12f, atOne, 0f)
        savePng("launch-card-row-label-size-thor-main-font-1.0")

        fontScale = 1.3f
        compose.waitForIdle()
        val atOneThree = assertOneSizeOnTheCard("thor-main font=1.3", parkedKeys, expectedRows = 1)
        assertTrue("at font 1.3 the long label forces the row below 12 sp, so a build where nothing steps down cannot pass: $atOneThree", atOneThree < 12f)
        savePng("launch-card-row-label-size-thor-main-font-1.3")
    }

    @Test
    fun `the three-button card of a game that is not parked also wears one size at fonts 1 and 1_3`() {
        mount(832.5f, 468.3f, MimirViewportPosture.STANDARD, parked = false)
        for (font in listOf(1.0f, 1.3f)) {
            fontScale = font
            compose.waitForIdle()
            assertOneSizeOnTheCard("unparked font=$font", unparkedKeys, expectedRows = 1)
        }
    }

    @Test
    fun `on a card that stacks the buttons in two rows both rows wear one size at fonts 1 and 1_3`() {
        mount(520f, 400f, MimirViewportPosture.STANDARD, parked = true)
        for (font in listOf(1.0f, 1.3f)) {
            fontScale = font
            compose.waitForIdle()
            assertOneSizeOnTheCard("stacked font=$font", parkedKeys, expectedRows = 2)
            if (font == 1.3f) savePng("launch-card-row-label-size-stacked-font-1.3")
        }
    }
}
