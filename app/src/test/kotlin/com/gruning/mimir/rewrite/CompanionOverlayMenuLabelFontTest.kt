package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/**
 * Thor Bottom revamp V9 / T9: the native ᛗ menu's words are IBM Plex, the product's face, where they were the platform's bold
 * (`Typeface.DEFAULT_BOLD`) at 11.5 and 9.5 sp. The title is `MimirTypeStep.Label` (11 sp) and a coin's caption
 * `MimirTypeStep.Micro` (9 sp), SemiBold as the capped eyebrow and micro-status roles they stand in for.
 *
 * `core-companion-native` has no core-ui dependency, so the app resolves the face (`MimirFonts.sansSemiBoldTypeface`) and hands it
 * to the controller in `startCompanionOverlay`, before the first mount. This is one native component on both screens: the same
 * words, in the same face, on Main's single-display menu and on the Bottom's.
 *
 * Plex has no runic glyphs, so the runes keep a system face: each coin's rune is `CompanionMenuCoinView`'s own paint, and the title's
 * rune is set in the platform face by a span.
 *
 * Run on the real controller, on real text shaping (native graphics) with the font resources, laid out at font scale 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionOverlayMenuLabelFontTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        idle()
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private class Menu(val title: TextView, val captions: List<TextView>, val coins: List<View>, val root: View)

    private fun openMenu(role: CompanionOverlayRole, scale: Float, returnAvailable: Boolean = false): Menu {
        RuntimeEnvironment.setFontScale(scale)
        val overlay = startCompanionOverlay(app, scope)
        // The Bottom's menu gains a fifth coin, "Return to shelf", while a game holds the shelf: three columns, a narrower slot.
        ReflectionHelpers.setField(overlay, "secondaryReturnAvailable", returnAvailable)
        val display = if (role == CompanionOverlayRole.PRIMARY_SINGLE) Display.DEFAULT_DISPLAY else bottomDisplayId
        overlay.targetOverlay(CompanionOverlayTarget(role, display))
        idle()
        // The controller builds from its display context, whose resources a real device gives the system's font scale. Robolectric's
        // setFontScale reaches the application's resources only, so the display context is handed the same scale here.
        val displayContext = checkNotNull(ReflectionHelpers.getField<android.content.Context?>(overlay, "displayContext")) { "fixture: mounted" }
        @Suppress("DEPRECATION")
        displayContext.resources.updateConfiguration(
            android.content.res.Configuration(displayContext.resources.configuration).apply { fontScale = scale },
            displayContext.resources.displayMetrics,
        )
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        val root = checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView"))
        val texts = root.descendants().filterIsInstance<TextView>().toList()
        val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, root.resources.displayMetrics) }
        val title = texts.single { it.textSize == sp(MimirTypeStep.Label.value) }
        val captions = texts.filter { it.textSize == sp(MimirTypeStep.Micro.value) }
        val coins = root.descendants().filter { it.javaClass.simpleName == "CompanionMenuCoinView" }.toList()
        assertTrue("fixture: the menu has its coins and their captions (${captions.size}, ${coins.size})", captions.size >= 3 && coins.size == captions.size)
        return Menu(title, captions, coins, root)
    }

    private fun both(check: (CompanionOverlayRole, Float) -> Unit) {
        listOf(CompanionOverlayRole.SECONDARY_COMPANION, CompanionOverlayRole.PRIMARY_SINGLE).forEach { role ->
            listOf(1.0f, 1.3f).forEach { scale ->
                resetOverlaySingleton()
                check(role, scale)
            }
        }
    }

    private fun assertCaptionsFit(menu: Menu, where: String, rows: StringBuilder) {
        val density = menu.root.resources.displayMetrics.density
        menu.root.measure(
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((900 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        menu.root.layout(0, 0, menu.root.measuredWidth, menu.root.measuredHeight)
        menu.captions.forEach { caption ->
            val layout = checkNotNull(caption.layout) { "laid out ($where)" }
            val text = caption.text.toString()
            assertTrue("`$text` is at most two lines ($where): ${layout.lineCount}", layout.lineCount in 1..2)
            assertEquals("`$text` is shown whole, none cut by maxLines ($where)", text.length, layout.getLineEnd(layout.lineCount - 1))
            (0 until layout.lineCount).forEach { line ->
                assertTrue(
                    "`$text` line $line fits the slot's width ($where): ${layout.getLineWidth(line)} of ${caption.width}",
                    layout.getLineWidth(line) <= caption.width,
                )
            }
            // The band is a fixed 28 dp. Caps carry no descender, so the last line's ink ends at its baseline.
            val inkBottom = layout.getLineBaseline(layout.lineCount - 1) + 1
            assertTrue("`$text`'s ink ends inside the 28 dp band ($where): $inkBottom of ${caption.height}", inkBottom <= caption.height)
            // The same, from pixels: the caption's layout drawn onto a canvas twice as tall as its band, so nothing is clipped by the
            // view's own bounds, and the lowest row that carries ink read off the bitmap.
            val bitmap = android.graphics.Bitmap.createBitmap(caption.width, caption.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
            caption.paint.color = android.graphics.Color.WHITE
            layout.draw(android.graphics.Canvas(bitmap))
            var lastInkRow = -1
            for (y in bitmap.height - 1 downTo 0) {
                if ((0 until bitmap.width).any { x -> (bitmap.getPixel(x, y) ushr 24) > 40 }) {
                    lastInkRow = y
                    break
                }
            }
            assertTrue("`$text` drew ink ($where)", lastInkRow >= 0)
            assertTrue("`$text`'s lowest ink row is inside the 28 dp band, so none is clipped ($where): row $lastInkRow of ${caption.height}", lastInkRow < caption.height)
            rows.append("$where | `$text` | lines=${layout.lineCount} | slot=${caption.width} px | text height=${layout.height} px | band=${caption.height} px | ink bottom=$inkBottom px | lowest ink row=$lastInkRow\n")
        }
        val coinSize = menu.coins.first().height
        assertTrue("the coins keep their size ($where): $coinSize px", coinSize >= (56 * density).toInt() - 1)
        assertTrue("the title fits one line ($where)", menu.title.layout.lineCount == 1 && menu.title.layout.getLineEnd(0) == menu.title.text.length)
    }

    @Test
    fun `the title and every caption are Plex SemiBold at Label 11 and Micro 9, on both screens, at both font scales`() {
        val plex = checkNotNull(MimirFonts.sansSemiBoldTypeface(app)) { "fixture: Plex SemiBold resolves from the font resources" }
        both { role, scale ->
            val menu = openMenu(role, scale)
            val where = "$role at font scale $scale"
            val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, menu.root.resources.displayMetrics) }
            assertEquals("title is Label 11 sp ($where)", sp(11f), menu.title.textSize, 0.01f)
            menu.captions.forEach {
                assertEquals("caption is Micro 9 sp ($where)", sp(9f), it.textSize, 0.01f)
                assertEquals("caption is Plex SemiBold ($where): ${it.text}", plex.weight, it.typeface.weight)
                assertNotEquals("not the platform bold ($where)", Typeface.DEFAULT_BOLD, it.typeface)
            }
            assertNotEquals("title is not the platform bold ($where)", Typeface.DEFAULT_BOLD, menu.title.typeface)
            assertEquals("title is Plex SemiBold ($where)", plex.weight, menu.title.typeface.weight)
            val controllerFace = ReflectionHelpers.getField<Typeface?>(
                ReflectionHelpers.getStaticField<AndroidCompanionOverlayController?>(AndroidCompanionOverlayController::class.java, "shared"),
                "menuLabelTypeface",
            )
            assertNotNull("the app handed the controller the face ($where)", controllerFace)
            assertEquals("every caption and the title wear it ($where)", setOf(controllerFace), (menu.captions + menu.title).map { it.typeface }.toSet())
        }
    }

    @Test
    fun `the runes keep a system face, in the coins and in the title`() {
        both { role, scale ->
            val menu = openMenu(role, scale)
            val where = "$role at font scale $scale"
            menu.coins.forEach { coin ->
                val runePaint = ReflectionHelpers.getField<Paint>(coin, "runePaint")
                assertEquals("a coin's rune is the platform face ($where)", Typeface.DEFAULT, runePaint.typeface)
            }
            val spanned = menu.title.text as Spanned
            val spans = spanned.getSpans(0, spanned.length, TypefaceSpan::class.java)
            assertEquals("the title's one rune carries its own face ($where)", 1, spans.size)
            assertEquals(0, spanned.getSpanStart(spans.single()))
            assertEquals(1, spanned.getSpanEnd(spans.single()))
            assertEquals("and it is the platform's", Typeface.DEFAULT_BOLD, spans.single().typeface)
            assertEquals("the words after it are not in the span", true, spanned.toString()[1] == ' ')
        }
        val source = File("../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt").readText()
        assertTrue("the rune paint is pinned to the platform face in source", source.contains("private val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {\n        typeface = Typeface.DEFAULT"))
    }

    @Test
    fun `the coins and their captions fit, whole and unclipped, at font scale 1_0 and 1_3`() {
        val rows = StringBuilder()
        both { role, scale -> assertCaptionsFit(openMenu(role, scale), "$role at font scale $scale", rows) }
        println("MENU-FIT\n$rows")
    }

    @Test
    fun `the Bottom's six-coin menu, with its two-line Return to shelf and the Trackpad coin, fits at font scale 1_0 and 1_3`() {
        val rows = StringBuilder()
        listOf(1.0f, 1.3f).forEach { scale ->
            resetOverlaySingleton()
            val menu = openMenu(CompanionOverlayRole.SECONDARY_COMPANION, scale, returnAvailable = true)
            // the Bottom menu carries a Trackpad coin (QuickMenuItem.TRACKPAD, last), a sixth with
            // Return to shelf available. It is the Bottom (SECONDARY_COMPANION) menu only: Main's menu has its own four coins.
            assertEquals("fixture: six coins", 6, menu.coins.size)
            assertCaptionsFit(menu, "six-coin Bottom menu at font scale $scale", rows)
        }
        println("MENU-FIT-5\n$rows")
    }

    @Test
    fun `line metrics, before and after, at both font scales`() {
        val plex = checkNotNull(MimirFonts.sansSemiBoldTypeface(app))
        val out = StringBuilder()
        fun metrics(label: String, face: Typeface, sizeSp: Float, scale: Float) {
            RuntimeEnvironment.setFontScale(scale)
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, app.resources.displayMetrics)
            val paint = Paint().apply { typeface = face; textSize = px; letterSpacing = 0.05f }
            val fm = paint.fontMetrics
            val line = fm.descent - fm.ascent + fm.leading
            val words = listOf("QUICK LAUNCH", "RETURN TO SHELF", "TABS", "UTILITY", "TWEAKER", "SWAP")
            val widest = words.maxOf { paint.measureText(it) }
            out.append(
                "%-34s scale %.1f | %5.2f sp -> %5.2f px | ascent %6.2f descent %5.2f leading %4.2f | line %5.2f px (2 lines %5.2f px) | widest label %6.2f px\n"
                    .format(label, scale, sizeSp, px, fm.ascent, fm.descent, fm.leading, line, 2 * line, widest),
            )
        }
        listOf(1.0f, 1.3f).forEach { scale ->
            metrics("coin caption BEFORE DEFAULT_BOLD", Typeface.DEFAULT_BOLD, 9.5f, scale)
            metrics("coin caption AFTER  Plex SemiBold", plex, 9f, scale)
            metrics("title BEFORE DEFAULT_BOLD", Typeface.DEFAULT_BOLD, 11.5f, scale)
            metrics("title AFTER  Plex SemiBold", plex, 11f, scale)
        }
        // The two-line caption, as the menu lays it out (a 73 dp slot: the five-coin menu's three columns), before and after.
        fun twoLine(label: String, face: Typeface, sizeSp: Float, scale: Float, fontPadding: Boolean) {
            RuntimeEnvironment.setFontScale(scale)
            val view = TextView(app).apply {
                text = "RETURN TO SHELF"
                textSize = sizeSp
                typeface = face
                letterSpacing = 0.05f
                maxLines = 2
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                includeFontPadding = fontPadding
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(73, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val l = view.layout
            out.append(
                "%-24s scale %.1f fontPadding=%-5s | lines=%d | height %d px | last baseline %d px | ink bottom ~%d px (band 28)\n"
                    .format(label, scale, fontPadding, l.lineCount, l.height, l.getLineBaseline(l.lineCount - 1), l.getLineBaseline(l.lineCount - 1) + 1),
            )
        }
        listOf(1.0f, 1.3f).forEach { scale ->
            twoLine("RETURN TO SHELF BEFORE", Typeface.DEFAULT_BOLD, 9.5f, scale, true)
            twoLine("RETURN TO SHELF AFTER", plex, 9f, scale, true)
            twoLine("RETURN TO SHELF AFTER", plex, 9f, scale, false)
        }
        println("MENU-METRICS (density ${app.resources.displayMetrics.density}; the band is 28 dp)\n$out")
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun advance(ms: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resetOverlaySingleton() {
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }
}
