package com.gruning.mimir.rewrite.feature.library

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ONE launch card, drawn from both doors . The same game, at Thor Main (1920 x 1080 px = 832.5 x 468.3 dp at density 2.30625) and on the 320 x 300 dp compact card, at
 * font 1.0 and 1.3, parked behind Home and not, is handed to Home's door (a [HomeLaunchCardUiState] and [HomeLaunchCardFocusContract], as `:app` builds it) and to the Library's
 * ([libraryLaunchCardUiState] and [LibraryLaunchCardFocusContract], as [GameDetailScreen] builds it). Both must show:
 *  - the same action labels in the same order (Play or Resume, Restart only while parked, Open with, Achievements);
 *  - the same fact labels, with a real SESSION TIME (a total, not "NOT TRACKED");
 *  - Restart while the game is parked, and not otherwise;
 *  - a Play ring that hugs its plate, read off the pixels;
 *  - the same card, pixel for pixel: nothing is left that only one door draws.
 * The window is larger than Main (900 x 600 dp) so the system bars cannot cut the card, which stands in a 1920 x 1080 px box of its own. The PNGs of every door, geometry, font scale and parked state are written to `build/onecard/` and `build/test-captures/onecard/round2/` (`<door>-<geometry>-<scale>-<state>.png`). Pixel values are the JVM's;
 * the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w900dp-h600dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchCardDoorParityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Door(val tag: String, val contract: MimirLaunchCardFocusContract) {
        HOME("home", HomeLaunchCardFocusContract),
        LIBRARY("library", LibraryLaunchCardFocusContract),
    }

    private enum class Geometry(val tag: String, val widthDp: Float, val heightDp: Float, val posture: MimirViewportPosture) {
        THOR("thor", 832.5f, 468.3f, MimirViewportPosture.STANDARD),
        COMPACT("compact", 320f, 300f, MimirViewportPosture.COMPACT_SQUARE),
    }

    private val density = 2.30625f
    private val layouts = mutableMapOf<String, TextLayoutResult>()
    private var door by mutableStateOf(Door.HOME)
    private var fontScale by mutableStateOf(1f)
    private var currentAuthority: DefaultFocusAuthority? = null
    private val cardTag = "door-parity-card"

    @After
    fun resetAnimatorsAndLease() {
        setAnimatorScale(1f)
        HomeLaunchCardResume.set(null)
    }

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

    // ---- the one game both doors are handed ----------------------------------------------------------------------------------------------

    private val gameId = GameId("ps1/xenogears")
    private val sessionMillis = 5_040_000L // "1 h 24 min", as the Bottom writes it
    private val sizeBytes = 614_400_000L
    private val members = listOf(
        GameMemberRow(entryKey = com.gruning.mimir.rewrite.core.model.EntryKey("p:1:/ROMs/ps1/Xenogears (USA) (Disc 1).cue"), discNumber = 1, displayName = "Xenogears (USA) (Disc 1).cue", sizeBytes = sizeBytes / 2),
        GameMemberRow(entryKey = com.gruning.mimir.rewrite.core.model.EntryKey("p:1:/ROMs/ps1/Xenogears (USA) (Disc 2).cue"), discNumber = 2, displayName = "Xenogears (USA) (Disc 2).cue", sizeBytes = sizeBytes / 2),
    )
    private val description = "A sprawling role-playing epic across two discs: a village, a war machine, and a god. Enough text to wrap onto several lines of the card's description."
    private val game = GameRow(
        gameId = gameId,
        title = "Xenogears",
        platformId = PlatformId.PS1,
        memberCount = 2,
        duplicateKind = null,
        genre = "Role-playing",
        region = "USA",
        version = "1.1",
        description = description,
    )

    /** Home's door, as `:app` builds the state at MimirAppHost: the row carries the session total, the facts come from the shared builder. */
    private fun homeState(): HomeLaunchCardUiState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(gameId),
            rank = 0L,
            addedAtMillis = 0L,
            title = game.title,
            supportingLabel = game.platformId.value,
            platformId = game.platformId,
            genre = game.genre,
            description = game.description,
            externalSessionElapsedMillis = sessionMillis,
        ),
        platformLabel = "Sony PlayStation",
        emulatorLabel = "RetroArch",
        cartridgeArtIsPhysical = launchCardFaceIsPhysical(null),
        extraFacts = launchCardFacts(game, launchCardSizeBytes(game, members), libraryDetailDiscs(members)),
    )

    /** The Library's door, as [GameDetailScreen] builds the state: the same game through the Library adapter. */
    private fun libraryState(): HomeLaunchCardUiState = libraryLaunchCardUiState(
        target = LibraryDetailTarget.Emulated(game),
        metadata = LibraryDetailMetadata(
            launcherLabel = "RetroArch",
            sizeBytes = launchCardSizeBytes(game, members),
            discs = libraryDetailDiscs(members),
        ),
        achievementProgress = null,
        statusLine = null,
        screenScraperConnected = false,
        platformLabel = "Sony PlayStation",
        cartridgeArtIsPhysical = launchCardFaceIsPhysical(null),
        externalSessionElapsedMillis = sessionMillis,
    )

    private fun stateFor(door: Door): HomeLaunchCardUiState = if (door == Door.HOME) homeState() else libraryState()

    private fun mount(geometry: Geometry) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        layouts.clear()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = density, fontScale = fontScale),
                LocalMimirViewportProfile provides MimirViewportProfile(geometry.posture, geometry.widthDp, geometry.heightDp),
                LocalMimirTextLayoutObserver provides { spoken: String, layout: TextLayoutResult -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredSize(geometry.widthDp.dp, geometry.heightDp.dp).testTag(cardTag)) {
                        key(door) {
                            val contract = door.contract
                            val authority = remember { DefaultFocusAuthority().apply { pushScope(contract.scope) } }
                            currentAuthority = authority
                            HomeLaunchCard(
                                state = stateFor(door),
                                onPlay = {},
                                onAchievements = {},
                                onOpenWith = {},
                                onClose = {},
                                authority = authority,
                                focusContract = contract,
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun show(door: Door, fontScale: Float, parked: Boolean, focus: FocusKey? = door.contract.playKey) {
        HomeLaunchCardResume.set(if (parked) gameId else null)
        compose.runOnIdle {
            this.door = door
            this.fontScale = fontScale
        }
        settle()
        val authority = checkNotNull(currentAuthority)
        compose.runOnIdle { if (focus != null) authority.requestFocus(focus) }
        settle()
    }

    // ---- what a door shows ---------------------------------------------------------------------------------------------------------------

    private fun cardBounds(): Rect = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow

    private fun buttonBounds(key: FocusKey): Rect? =
        compose.onAllNodes(hasTestTag(homeLaunchCardActionTestTag(key)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    /** The action buttons on the card by their spoken label (the click action's), in reading order: row by row, left to right. */
    private fun actionLabels(door: Door): List<String> {
        val c = door.contract
        return listOf(c.playKey, c.restartKey, c.openWithKey, c.achievementsKey).mapNotNull { key ->
            val node = compose.onAllNodes(hasTestTag(homeLaunchCardActionTestTag(key)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                ?: return@mapNotNull null
            Triple(node.boundsInWindow, node.config.getOrNull(SemanticsActions.OnClick)?.label, key)
        }
            .sortedWith(compareBy({ Math.round(it.first.top / 4f) }, { it.first.left }))
            .map { checkNotNull(it.second) { "button ${it.third.value} speaks no label" } }
    }

    private fun shown(text: String, ignoreCase: Boolean = true): Boolean =
        compose.onAllNodes(hasText(text, ignoreCase = ignoreCase) or hasContentDescription(text, ignoreCase = ignoreCase), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private val factLabels = listOf("EMULATOR", "GENRE", "SESSION TIME", "LAST PLAYED", "REGION", "VERSION", "SIZE", "DISCS")

    private fun factLabelsShown(): List<String> = factLabels.filter { shown(it) }

    /** A button's label as it laid out: whole on one line, not cut by an ellipsis. */
    private fun labelIsWhole(label: String): Boolean {
        val layout = layouts[label] ?: return false
        return layout.lineCount == 1 && (0 until layout.lineCount).none { layout.isLineEllipsized(it) } && !layout.didOverflowWidth
    }

    private fun capture(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun cardPixels(): Bitmap {
        val whole = capture()
        val card = cardBounds()
        val left = card.left.toInt().coerceIn(0, whole.width - 1)
        val top = card.top.toInt().coerceIn(0, whole.height - 1)
        val right = card.right.toInt().coerceIn(left + 1, whole.width)
        val bottom = card.bottom.toInt().coerceIn(top + 1, whole.height)
        return Bitmap.createBitmap(whole, left, top, right - left, bottom - top)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/onecard").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures/onecard/round2")
        if (shared.parentFile?.isDirectory == true) {
            shared.mkdirs()
            out.copyTo(File(shared, name), overwrite = true)
        }
        println("ONECARD wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun differingPixels(a: Bitmap, b: Bitmap): Pair<Int, IntArray?> {
        assertEquals("the two doors' cards are one size", a.width to a.height, b.width to b.height)
        var count = 0
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) {
                    count++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        return count to (if (count == 0) null else intArrayOf(minX, minY, maxX, maxY))
    }

    // ---- the ring ---------------------------------------------------------------------------------------------------------------------

    private fun rgbDistance(a: Int, b: Int): Int =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

    /** Pixels between the plate's edge and the first pixel the ring changed (focused frame against the rest frame), walking outward; null when none within 16 px. */
    private fun gapToRing(focused: Bitmap, rest: Bitmap, startX: Int, startY: Int, stepX: Int, stepY: Int): Int? {
        for (i in 0 until 16) {
            val x = startX + stepX * i
            val y = startY + stepY * i
            if (x !in 0 until focused.width || y !in 0 until focused.height) return null
            if (rgbDistance(focused.getPixel(x, y), rest.getPixel(x, y)) > 40) return i
        }
        return null
    }

    /**
     * The Play ring hugs its plate, read off the pixels and not off the layout boxes (which agree by construction): the ring is the pixels that change when focus moves
     * on, so the frame with Play lit is set against the one with Open with lit. Walking outward from the plate's edge, the ring starts within 3 px of it on every side (the
     * middle of the two ends, a quarter, a half and three quarters along the top and the bottom), and opposite sides agree within 2 px, so it is centred on the plate.
     */
    private fun assertPlayRingHugsItsPlate(door: Door, geometry: Geometry, fontScale: Float, parked: Boolean) {
        val label = "${door.tag} ${geometry.tag} font=$fontScale parked=$parked"
        show(door, fontScale, parked, focus = door.contract.openWithKey)
        val rest = cardPixels()
        show(door, fontScale, parked, focus = door.contract.playKey)
        val lit = cardPixels()
        val card = cardBounds()
        val play = checkNotNull(buttonBounds(door.contract.playKey)) { "$label: Play is not on the card" }
            .translate(-card.left, -card.top)
        val left = play.left.toInt()
        val right = play.right.toInt()
        val top = play.top.toInt()
        val bottom = play.bottom.toInt()
        val midY = (top + bottom) / 2
        val gaps = linkedMapOf<String, Int?>(
            "left" to gapToRing(lit, rest, left - 1, midY, -1, 0),
            "right" to gapToRing(lit, rest, right, midY, 1, 0),
        )
        for (quarter in 1..3) {
            val x = left + (right - left) * quarter / 4
            gaps["top@$quarter"] = gapToRing(lit, rest, x, top - 1, 0, -1)
            gaps["bottom@$quarter"] = gapToRing(lit, rest, x, bottom, 0, 1)
        }
        gaps.forEach { (side, gap) ->
            assertNotNull("$label: the ring's stroke is within 16 px of the plate's $side edge: $gaps", gap)
            assertTrue("$label: the ring hugs the plate at $side (a $gap px gap, at most 3 allowed): $gaps", checkNotNull(gap) in 0..3)
        }
        val tops = (1..3).map { checkNotNull(gaps.getValue("top@$it")) }
        val bottoms = (1..3).map { checkNotNull(gaps.getValue("bottom@$it")) }
        assertTrue(
            "$label: opposite sides agree within 2 px, so the ring is centred on the plate: $gaps",
            abs(checkNotNull(gaps.getValue("left")) - checkNotNull(gaps.getValue("right"))) <= 2 && abs(tops.average() - bottoms.average()) <= 2.0,
        )
    }

    // ---- the checks ---------------------------------------------------------------------------------------------------------------------

    private fun runGeometry(geometry: Geometry, assertEveryLabelWhole: Boolean) {
        mount(geometry)
        for (fontScale in listOf(1.0f, 1.3f)) {
            for (parked in listOf(false, true)) {
                val state = if (parked) "parked" else "unparked"
                val name = "${geometry.tag} font=$fontScale $state"
                val expectedLabels = if (parked) listOf("Resume", "Restart", "Open with", "Achievements") else listOf("Play", "Open with", "Achievements")

                val seen = mutableMapOf<Door, Triple<List<String>, List<String>, Bitmap>>()
                for (door in Door.values()) {
                    show(door, fontScale, parked)
                    val labels = actionLabels(door)
                    val facts = factLabelsShown()
                    val pixels = cardPixels()
                    save(pixels, "${door.tag}-${geometry.tag}-${"%.1f".format(fontScale)}-$state.png")
                    println("ONECARD $name ${door.tag}: actions=$labels facts=$facts")
                    seen[door] = Triple(labels, facts, pixels)

                    // Restart is there while parked and only then; the labels are the card's.
                    assertEquals("$name ${door.tag}: the actions, in reading order", expectedLabels.map { it.lowercase() }, labels.map { it.lowercase() })
                    assertEquals("$name ${door.tag}: Restart only while parked", parked, buttonBounds(door.contract.restartKey) != null)
                    // SESSION TIME is a real total on both doors, never the "not tracked" sentence, wherever the card has room to list it.
                    if ("SESSION TIME" in facts) {
                        assertTrue("$name ${door.tag}: SESSION TIME is real: 1 h 24 min", shown("1 h 24 min", ignoreCase = false))
                        assertTrue("$name ${door.tag}: it is not the sentence for an untracked game", !shown("NOT TRACKED"))
                    }
                    if (assertEveryLabelWhole) {
                        labels.forEach { label -> assertTrue("$name ${door.tag}: the label '$label' stands whole on one line", labelIsWhole(label)) }
                    }
                }
                val home = seen.getValue(Door.HOME)
                val library = seen.getValue(Door.LIBRARY)
                assertEquals("$name: both doors show the same action labels in the same order", home.first, library.first)
                assertEquals("$name: both doors show the same fact labels", home.second, library.second)
                val (differing, box) = differingPixels(home.third, library.third)
                assertEquals("$name: the two doors draw the same card, pixel for pixel (differing region ${box?.toList()})", 0, differing)
            }
        }
    }

    @Test
    fun `at Thor Main both doors show one card, with a real SESSION TIME and Restart while parked`() {
        runGeometry(Geometry.THOR, assertEveryLabelWhole = true)
    }

    @Test
    fun `on the 320 x 300 compact card both doors show one card`() {
        // The compact floor's button labels at 1.3 are measured and printed by HomeLaunchCardRestartFitTest, not asserted; parity (the two doors alike) is what is asserted here.
        runGeometry(Geometry.COMPACT, assertEveryLabelWhole = false)
    }

    @Test
    fun `at Thor Main the Play ring hugs its plate on both doors`() {
        mount(Geometry.THOR)
        for (door in Door.values()) {
            for (fontScale in listOf(1.0f, 1.3f)) {
                for (parked in listOf(false, true)) {
                    assertPlayRingHugsItsPlate(door, Geometry.THOR, fontScale, parked)
                }
            }
        }
    }

    @Test
    fun `on the compact card the Play ring hugs its plate on both doors`() {
        mount(Geometry.COMPACT)
        for (door in Door.values()) {
            for (fontScale in listOf(1.0f, 1.3f)) {
                for (parked in listOf(false, true)) {
                    assertPlayRingHugsItsPlate(door, Geometry.COMPACT, fontScale, parked)
                }
            }
        }
    }

    // ---- About ends on a whole line (sweep 10-13) -----------------------------------------------------------------------------------

    /**
     * The wide card gives About the height left under the fact rows, in whole lines. At Thor 1.3 with REGION, VERSION, SIZE and DISCS listed that is one or two lines of a
     * longer text: the viewport ends on a line (never a half-cut one), the down arrow shows, and the description is a registered stop, so UP from Play reaches it. Where the
     * whole text fits there is no arrow and no stop.
     */
    @Test
    fun `at Thor Main About ends on a whole line, and a cut description shows its arrow and is reachable`() {
        mount(Geometry.THOR)
        for (door in Door.values()) {
            for (fontScale in listOf(1.0f, 1.3f)) {
                for (parked in listOf(false, true)) {
                    show(door, fontScale, parked)
                    val name = "${door.tag} thor font=$fontScale parked=$parked"
                    val node = compose.onAllNodes(hasText(description), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    assertNotNull("$name: About is drawn", node)
                    val results = mutableListOf<TextLayoutResult>()
                    node!!.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    val totalLines = checkNotNull(results.firstOrNull()) { "$name: the description reports no layout" }.lineCount
                    // The viewport's own height: whole 19 sp lines and 4 dp of inset above and below.
                    val viewport = compose.onAllNodes(hasTestTag(HomeLaunchCardDescriptionViewportTestTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    assertNotNull("$name: About has a viewport", viewport)
                    val viewportDp = viewport!!.boundsInWindow.height / density
                    // 19 sp as this font scale draws it (the scale is not linear: 19 sp is about 22.6 dp at 1.3, not 24.7).
                    val lineDp = with(Density(density, fontScale)) { 19.sp.toDp().value }
                    val lines = (viewportDp - 8f) / lineDp
                    val cut = totalLines > Math.round(lines)
                    if (cut) {
                        // A cut description's viewport is whole lines: its last visible line is never half of one.
                        assertTrue("$name: the viewport ends on a whole line ($lines of $totalLines lines)", abs(lines - Math.round(lines)) < 0.06f && Math.round(lines) >= 1)
                    } else {
                        // A description that fits is as tall as its text (a line's trimmed leading aside).
                        assertTrue("$name: a description that fits is shown whole ($lines of $totalLines lines)", abs(lines - totalLines) < 0.2f)
                    }
                    assertEquals("$name: the down arrow shows exactly when the text is cut ($totalLines lines, $lines shown)", cut, shown("\u2193", ignoreCase = false))
                    val authority = checkNotNull(currentAuthority)
                    assertEquals(
                        "$name: a cut description is a registered stop, so UP from Play reaches it",
                        cut,
                        door.contract.descriptionKey in authority.registeredKeysInScope(door.contract.scope),
                    )
                    println("ONECARD $name about: $totalLines lines, $lines shown, cut=$cut")
                }
            }
        }
        // At 1.3 with all four facts the card is short of room: that case must be a cut one, or this proves nothing.
        show(Door.HOME, 1.3f, parked = false)
        assertTrue("the four-fact Thor 1.3 card is the cut case", shown("\u2193", ignoreCase = false))
    }

    /** The fallback book prints the platform only on the wide card: the compact card's header already prints it just above the book. */
    @Test
    fun `the compact card's book leaves the platform to the header`() {
        mount(Geometry.COMPACT)
        show(Door.HOME, 1.0f, parked = false)
        assertTrue("the header prints the platform", shown("Sony PlayStation", ignoreCase = false))
        assertTrue("the compact book does not print it a second time", !shown("SONY PLAYSTATION", ignoreCase = false))
    }

    @Test
    fun `the wide card's book keeps its platform line`() {
        mount(Geometry.THOR)
        show(Door.HOME, 1.0f, parked = false)
        assertTrue("the wide book keeps its platform line", shown("SONY PLAYSTATION", ignoreCase = false))
    }

    /**
     * RC-20 (audit 2026-10-14, H-04): "ABOUT indented about 4 px". The description's text is inset 4 dp inside its viewport so the selection ring has room round it,
     * and the viewport used to start on the column's edge, so the text stood 4 dp in from the heading above it. The viewport now starts [HomeLaunchDescriptionContentInset]
     * out to the start of the column, so its text starts on the heading's edge and the ring keeps its air outside the column. Its right edge stays on the column's.
     */
    @Test
    fun `at Thor Main the About text starts on the heading's edge on both doors`() {
        mount(Geometry.THOR)
        for (door in Door.values()) {
            show(door, 1.0f, parked = false)
            // The fact labels above it stand on the column's edge, as the heading does; EMULATOR is the first of them.
            val heading = compose.onAllNodes(hasText("EMULATOR", ignoreCase = true) or hasContentDescription("EMULATOR", ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            val viewport = compose.onAllNodes(hasTestTag(HomeLaunchCardDescriptionViewportTestTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            assertNotNull("${door.tag}: the fact column is drawn", heading)
            assertNotNull("${door.tag}: the About viewport is drawn", viewport)
            val insetPx = HomeLaunchDescriptionContentInset.value * density
            assertEquals(
                "${door.tag}: the viewport starts one inset out from the column, so its text starts on the column's edge (the facts and the heading above it)",
                heading!!.boundsInWindow.left - insetPx,
                viewport!!.boundsInWindow.left,
                1.01f,
            )
        }
    }

    // ---- the pieces both doors share ----------------------------------------------------------------------------------------------------

    @Test
    fun `the facts are one builder, in one order, and leave out what a game does not have`() {
        val facts = launchCardFacts(game, launchCardSizeBytes(game, members), libraryDetailDiscs(members))
        assertEquals(listOf("REGION", "VERSION", "SIZE", "DISCS"), facts.map { it.first })
        assertEquals("2", facts.single { it.first == "DISCS" }.second)

        val bare = GameRow(gameId = GameId("g"), title = "Bare", platformId = PlatformId.PS1, memberCount = 1, duplicateKind = null)
        assertEquals("no region, version, size or second disc: no facts", emptyList<Pair<String, String>>(), launchCardFacts(bare, launchCardSizeBytes(bare, emptyList()), emptyList()))
        assertEquals("a Play of a single disc lists no DISCS", emptyList<Pair<String, String>>(), launchCardFacts(null, null, listOf(LibraryDetailDisc(1, "a.cue"))))
    }

    /** The compact card draws no fact rows on either door (it is the art's card), so the total is pinned where both doors are handed it: the state each door builds. */
    @Test
    fun `both doors hand the card the same real session total, and the same facts`() {
        assertEquals(sessionMillis, homeState().row.externalSessionElapsedMillis)
        assertEquals(sessionMillis, libraryState().row.externalSessionElapsedMillis)
        assertEquals(homeState().extraFacts, libraryState().extraFacts)
        assertEquals(homeState().emulatorLabel, libraryState().emulatorLabel)
        assertEquals(
            "a Library card for a game with no tracked time says so, as Home's does",
            null,
            libraryLaunchCardUiState(
                target = LibraryDetailTarget.Emulated(game),
                metadata = LibraryDetailMetadata(),
                achievementProgress = null,
                statusLine = null,
                screenScraperConnected = false,
            ).row.externalSessionElapsedMillis,
        )
    }

    /**
     * What is left of the Library's own card plumbing is pinned where it is written: its adapter names its contract and has no second action of its own; the dispatcher has no
     * hand-mirrored ladder; the card block reads the shared physical-face rule.
     */
    @Test
    fun `the Library's door is the thin adapter, with no ladder or bounds of its own`() {
        val detail = source("GameDetailScreen.kt")
        assertTrue(detail.contains("focusContract = LibraryLaunchCardFocusContract,"))
        assertTrue("no second action in the card's row", !detail.contains("extraAction"))
        assertTrue("no Library-only bounds solver", !detail.contains("fun libraryDetailActionBounds") && !detail.contains("fun registerLibraryDetailSessionActions"))
        val dispatch = source("LibraryDispatch.kt")
        assertTrue(dispatch.contains("contract = LibraryLaunchCardFocusContract,"))
        assertTrue("no hand-mirrored ladder", !dispatch.contains("moveLibraryDetailActionFocus") && !dispatch.contains("libraryDetailActionFor"))
        val library = source("LibraryScreen.kt")
        assertTrue(library.contains("cartridgeArtIsPhysical = launchCardFaceIsPhysical(launchCardFace?.physical),"))
        assertTrue("the Library's old reading is gone", !library.contains("physical != false"))
    }

    private fun source(name: String): String {
        val dir = "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/"
        return listOf(File("feature-library/$dir$name"), File("$dir$name")).firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `no face is not physical media on either door`() {
        assertEquals(false, launchCardFaceIsPhysical(null))
        assertEquals(true, launchCardFaceIsPhysical(true))
        assertEquals(false, launchCardFaceIsPhysical(false))
        assertEquals("the Library adapter keeps what it is handed", true, libraryState().copy(cartridgeArtIsPhysical = true).cartridgeArtIsPhysical)
        assertEquals("both doors start a card with no resolved face as not physical", homeState().cartridgeArtIsPhysical, libraryState().cartridgeArtIsPhysical)
    }
}
