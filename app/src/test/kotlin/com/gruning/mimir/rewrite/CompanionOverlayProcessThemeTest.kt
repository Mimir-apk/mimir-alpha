package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionButtonMark
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTheme
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirAccentStop
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirButtonMark
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.withAppearanceOverrides
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerCommand
import com.gruning.mimir.rewrite.feature.settings.MimirButtonPickerMark
import com.gruning.mimir.rewrite.feature.settings.MimirButtonRuneKey
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerInitialState
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerOnFocus
import com.gruning.mimir.rewrite.feature.settings.mimirButtonPickerTransition
import com.gruning.mimir.rewrite.feature.settings.mimirButtonTileKey
import com.gruning.mimir.rewrite.feature.settings.previewMark
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

/**
 * Round 5d: after a restart, the Bottom's native ᛗ wore Default gold on a cover already in the player's theme.
 *
 * On the Thor (build bd8f88db, round 5c), with Hel stored, Mimir's process was ended. 1.25 s later the floor's cover was
 * Hel crimson and the ᛗ button was Default gold. Round 5c seeded the Bottom's payload from ThemeStore at process start,
 * and the ᛗ does not read the payload. It is a native View in `AndroidCompanionOverlayController`, which builds the
 * button, the menu card and its coins from one `CompanionOverlayTheme` field that starts as `CompanionOverlayTheme.Default`.
 * The only push into that field was MainActivity's ThemeStore collector, while `MButtonTopologyController` targets the ᛗ
 * from process start, before any MainActivity exists.
 *
 * Each test runs the real controller in a process that holds what a restart holds: the appearance file the previous
 * process left, holders nothing has loaded, no MainActivity and no Presentation. Process start runs in
 * `MimirApplication.onCreate`'s order: round 5c's seed, then the companion runtime's overlay start. The ᛗ is then
 * targeted on a second display, as the topology targets the Thor's Bottom, and mounts through WindowManager.
 *
 * The paint is read from the mounted views: the `theme` of each coin and of the menu card, the one material their onDraw
 * fills and strokes from. Pixels on the Thor are device-owed.
 *
 * Round 5d follow-up: the covers wear `MimirTheme`'s palette, the authored one with the player's appearance overrides
 * applied, while the overlay mapped the authored palette alone. An accent stop on Default, or a temperature on a named
 * theme, retints `accentBright` and the hero's `hardware`, which the menu card strokes as its rim and its sheen line, so
 * with an override set the open menu's rim and sheen did not match the covers. Those tests compare every overlay role,
 * field by field, with the token it names in `MimirThemePalette.forId(id).withAppearanceOverrides()`, the lookup
 * `MimirTheme` resolves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionOverlayProcessThemeTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope
    private var bottomDisplayId = -1

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        resetAppearanceToUnloaded()
        CompanionState.set(CompanionPayload.idle())
        resetOverlaySingleton()
        ShadowSettings.setCanDrawOverlays(true)
        // Same Robolectric 4.14.1 recipe as MButtonBottomForeignAppCoverTest.
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        // The companion runtime's process scope, minus its failure handler, so a failure here fails the test.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        idleMain()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        resetOverlaySingleton()
        resetAppearanceToUnloaded()
        CompanionState.set(CompanionPayload.idle())
        // The appearance loads write Compose snapshot state outside any composition; apply it and run what it posted, or
        // later Compose tests in this sandbox fail "Compose did not get idle" (see CompanionThemeSeedTest.tearDown).
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a fresh process mounts the Bottom's ᛗ in the stored theme before Main or the Presentation has pushed`() {
        storeTheme(MimirThemeId.HEL)
        val hel = companionOverlayTheme(MimirThemeId.HEL)
        assertNotEquals("fixture: Hel's material must differ from the overlay's built-in Default", CompanionOverlayTheme.Default, hel)

        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        val materialBeforeAnyMount = overlay.buildMaterial()
        mountOnBottom(overlay)

        assertEquals("the mounted ᛗ's paint: its disc, rim and mark", hel, overlay.mountedButtonPaint())
        assertEquals(
            "the material was already the stored theme's before the ᛗ was targeted, so no frame of it can be drawn in Default",
            hel,
            materialBeforeAnyMount,
        )
    }

    @Test
    fun `a theme chosen after the ᛗ mounted repaints it with no MainActivity in the process`() {
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        val target = mountOnBottom(overlay)
        val hel = companionOverlayTheme(MimirThemeId.HEL)
        assertNotEquals("fixture: before the change the ᛗ is not already in Hel", hel, overlay.mountedButtonPaint())

        // What the Appearance picker and the `mimir.setTheme` dev hook call.
        ThemeStore.setTheme(app, MimirThemeId.HEL)
        idleMain()

        assertEquals("fixture: the ᛗ stayed mounted on the Bottom through the change", target, overlay.activeTarget.value)
        assertEquals("the ᛗ repainted in the theme chosen after it mounted", hel, overlay.mountedButtonPaint())
    }

    @Test
    fun `the menu a long press opens in a fresh process wears the stored theme on its card, title and coins`() {
        storeTheme(MimirThemeId.HEL)
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)

        longPress(overlay.mountedButton())
        assertTrue("fixture: the long press opened the menu", overlay.quickMenuOpen.value)

        val hel = companionOverlayTheme(MimirThemeId.HEL)
        val menu = overlay.mountedRoot().descendants().toList()
        val cards = menu.filter { it.javaClass.simpleName == "CompanionMenuGlassView" }
        val coins = menu.filter { it.javaClass.simpleName == "CompanionMenuCoinView" }
        val title = menu.filterIsInstance<TextView>().filter { it.text.toString() == companionOverlayMenuTitleText }
        assertEquals("fixture: the menu has one card", 1, cards.size)
        assertTrue("fixture: the menu has its coins (found ${coins.size})", coins.size >= 3)
        assertEquals("fixture: the menu has its title", 1, title.size)

        assertEquals("the menu card's glass", hel, paintOf(cards.single()))
        coins.forEachIndexed { index, coin -> assertEquals("menu coin $index", hel, paintOf(coin)) }
        assertEquals("the menu title's ink", hel.inkArgb, title.single().currentTextColor)
    }

    @Test
    fun `a fresh process mounts the ᛗ and opens its menu in the stored accent stop the covers wear`() {
        val authoredMutedInk = companionOverlayTheme(MimirThemeId.DEFAULT).mutedInkArgb
        storeAccentStop(MimirAccentStop.ROSE_GOLD)

        seedCompanionThemeAtProcessStart(app)
        assertEquals("fixture: process start loaded the stored stop", MimirAccentStop.ROSE_GOLD, MimirAppearanceOverrides.accentStop)
        val covers = coversPalette(MimirThemeId.DEFAULT)
        assertOverrideRetintsCardRimAndSheen(MimirThemeId.DEFAULT, covers)

        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)

        assertWearsCovers("the mounted ᛗ", covers, authoredMutedInk, overlay.mountedButtonPaint())
        assertMenuWearsCovers(overlay, covers, authoredMutedInk)
        assertEquals(
            "one push at process start, and its device log line names what it carried",
            listOf(
                "overlay theme pushed theme=${MimirThemeId.DEFAULT.storageKey} accent=ROSE_GOLD temp=ORIGINAL " +
                    "cardRim=${hex(covers.accentBright.toArgb())} cardSheen=${hex(covers.materials.hero.hardware.toArgb())} " +
                    "mark=rune pushUs=<n>",
            ),
            overlayPushLines(),
        )
    }

    @Test
    fun `a fresh process mounts the ᛗ and opens its menu in the stored temperature of a named theme`() {
        storeTheme(MimirThemeId.HEL)
        val authoredMutedInk = companionOverlayTheme(MimirThemeId.HEL).mutedInkArgb
        storeAccentTemp(MimirThemeId.HEL, MimirAccentTemp.COOLER)

        seedCompanionThemeAtProcessStart(app)
        assertEquals(
            "fixture: process start loaded Hel's stored temperature",
            mapOf(MimirThemeId.HEL to MimirAccentTemp.COOLER),
            MimirAppearanceOverrides.accentTemps,
        )
        val covers = coversPalette(MimirThemeId.HEL)
        assertOverrideRetintsCardRimAndSheen(MimirThemeId.HEL, covers)

        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)

        assertWearsCovers("the mounted ᛗ", covers, authoredMutedInk, overlay.mountedButtonPaint())
        assertMenuWearsCovers(overlay, covers, authoredMutedInk)
        assertEquals(
            "one push at process start, and its device log line names what it carried",
            listOf(
                "overlay theme pushed theme=${MimirThemeId.HEL.storageKey} accent=none temp=COOLER " +
                    "cardRim=${hex(covers.accentBright.toArgb())} cardSheen=${hex(covers.materials.hero.hardware.toArgb())} " +
                    "mark=rune pushUs=<n>",
            ),
            overlayPushLines(),
        )
    }

    @Test
    fun `an accent stop tapped in the picker after the ᛗ mounted repaints it and the menu it opens with no MainActivity`() {
        val authoredMutedInk = companionOverlayTheme(MimirThemeId.DEFAULT).mutedInkArgb
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        val target = mountOnBottom(overlay)
        assertWearsCovers(
            "fixture: before the change, the mounted ᛗ",
            coversPalette(MimirThemeId.DEFAULT),
            authoredMutedInk,
            overlay.mountedButtonPaint(),
        )
        val buttonBeforePreview = overlay.mountedButton()

        // Browsing: AccentPickerOverlay writes a live preview for each row its focus lands on (AccentPicker.kt:76-87).
        MimirAppearanceOverrides.accentPreview = MimirAccentStop.CHAMPAGNE
        applySnapshotWrites()
        assertSame(
            "a live preview alone leaves the ᛗ's window as it was: no re-add for each row the picker's focus visits",
            buttonBeforePreview,
            overlay.mountedButton(),
        )

        // A tap on Rose Gold. MimirSelectOverlay focuses the row and selects it in one call (MimirSelectOverlay.kt:394), and
        // the picker writes the new row's preview later, from its focus collector, so the commit runs with Champagne still
        // previewed. commitAccent persists the stop, then closeAccentPicker clears both previews, and nothing ticks after
        // that clear (MimirSettingsCoordinator.kt:345-358).
        AppearanceOverrideStore.setAccent(app, MimirAccentStop.ROSE_GOLD)
        MimirAppearanceOverrides.accentPreview = null
        MimirAppearanceOverrides.accentTempPreview = null
        applySnapshotWrites()

        val roseGold = coversPalette(MimirThemeId.DEFAULT)
        assertOverrideRetintsCardRimAndSheen(MimirThemeId.DEFAULT, roseGold)
        assertEquals("fixture: the ᛗ stayed mounted on the Bottom through the change", target, overlay.activeTarget.value)
        assertWearsCovers("the mounted ᛗ after the commit", roseGold, authoredMutedInk, overlay.mountedButtonPaint())
        assertMenuWearsCovers(overlay, roseGold, authoredMutedInk)
    }

    @Test
    fun `a temperature committed for a named theme after the ᛗ mounted repaints it with no MainActivity`() {
        storeTheme(MimirThemeId.HEL)
        val authoredMutedInk = companionOverlayTheme(MimirThemeId.HEL).mutedInkArgb
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        val target = mountOnBottom(overlay)
        assertWearsCovers(
            "fixture: before the change, the mounted ᛗ",
            coversPalette(MimirThemeId.HEL),
            authoredMutedInk,
            overlay.mountedButtonPaint(),
        )

        // The D-pad: the focus rests on Cooler, whose preview is written, then A commits that row and closes the picker.
        MimirAppearanceOverrides.accentTempPreview = MimirAccentTemp.COOLER
        applySnapshotWrites()
        AppearanceOverrideStore.setAccentTemp(app, MimirThemeId.HEL, MimirAccentTemp.COOLER)
        MimirAppearanceOverrides.accentPreview = null
        MimirAppearanceOverrides.accentTempPreview = null
        applySnapshotWrites()

        val cooler = coversPalette(MimirThemeId.HEL)
        assertOverrideRetintsCardRimAndSheen(MimirThemeId.HEL, cooler)
        assertEquals("fixture: the ᛗ stayed mounted on the Bottom through the change", target, overlay.activeTarget.value)
        assertWearsCovers("the mounted ᛗ after the commit", cooler, authoredMutedInk, overlay.mountedButtonPaint())
    }

    /**
     * Concept A: the Mimir-button picker previews a mark on its own stage and only A pushes it to the live ᛗ. The picker's
     * focus moves over a saved mark's tile: no store call, no push, and the mounted ᛗ is the same window it was (each push
     * that changes the theme re-adds it, so one per previewed tile would do that on every focus move). A on the tile is
     * `MimirButtonPickerCommand.Commit`, which the settings coordinator hands to `AppearanceOverrideStore.setButtonMark`: its
     * revision tick makes exactly one push, its log line names the mark by id, and the ᛗ is rebuilt wearing it. A on the rune
     * then puts the rune back.
     */
    @Test
    fun `a mark previewed in the Mimir-button picker pushes nothing and A commits it to the mounted ᛗ once`() {
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)
        val before = overlay.mountedButton()
        assertEquals(CompanionButtonMark.Rune, paintOf(before).mark)
        ShadowLog.clear()

        val id = "a".repeat(32)
        val path = app.filesDir.resolve("appearance/marks/$id.png").absolutePath
        val saved = listOf(MimirButtonPickerMark(id, "Your mark 1", path))
        var state = mimirButtonPickerInitialState(current = null, saved = saved)

        // Browsing: focus lands on the tile. The stage previews it; nothing else does.
        state = mimirButtonPickerOnFocus(state, mimirButtonTileKey(id))
        assertEquals("the stage would show the mark", MimirButtonMark(id, path), state.previewMark())
        applySnapshotWrites()
        assertSame("a preview leaves the ᛗ's window as it was", before, overlay.mountedButton())
        assertEquals("a preview pushes no theme", emptyList<String>(), overlayPushLines())
        assertEquals("and nothing is committed", null, MimirAppearanceOverrides.buttonMark)

        // A on the tile: the picker's one command that reaches the store.
        val commit = mimirButtonPickerTransition(state, ControllerAction.CONFIRM, mimirButtonTileKey(id)).command
        assertEquals(MimirButtonPickerCommand.Commit(MimirButtonMark(id, path)), commit)
        AppearanceOverrideStore.setButtonMark(app, (commit as MimirButtonPickerCommand.Commit).mark)
        applySnapshotWrites()

        assertNotSame("the commit rebuilt the ᛗ", before, overlay.mountedButton())
        assertEquals(CompanionButtonMark.Image(id, path), paintOf(overlay.mountedButton()).mark)
        val covers = coversPalette(MimirThemeId.DEFAULT)
        assertEquals(
            "one push, and its log line names the mark by id, never by path",
            listOf(
                "overlay theme pushed theme=${MimirThemeId.DEFAULT.storageKey} accent=CLASSIC_GOLD temp=ORIGINAL " +
                    "cardRim=${hex(covers.accentBright.toArgb())} cardSheen=${hex(covers.materials.hero.hardware.toArgb())} " +
                    "mark=$id pushUs=<n>",
            ),
            overlayPushLines(),
        )

        // A on the rune tile: back to the shipping mark, one more push.
        val rune = mimirButtonPickerTransition(state, ControllerAction.CONFIRM, MimirButtonRuneKey).command
        assertEquals(MimirButtonPickerCommand.Commit(null), rune)
        AppearanceOverrideStore.setButtonMark(app, null)
        applySnapshotWrites()
        assertEquals(CompanionButtonMark.Rune, paintOf(overlay.mountedButton()).mark)
        assertTrue(overlayPushLines().last().endsWith(" mark=rune pushUs=<n>"))
    }

    /**
     * A committed mark whose file is gone (the copy is app-private, but a hand-deleted or truncated file is real) must not
     * leave the ᛗ without a mark: the theme still carries the mark, the coin strikes the shipping rune instead, and the
     * device log says so.
     */
    @Test
    fun `a committed mark whose file is gone leaves the rune on the mounted ᛗ and logs mark=rune reason=missing`() {
        seedCompanionThemeAtProcessStart(app)
        val overlay = startCompanionOverlay(app, scope)
        mountOnBottom(overlay)
        val id = "b".repeat(32)
        val path = app.filesDir.resolve("appearance/marks/$id.png").absolutePath
        assertTrue("fixture: the file is not there", !java.io.File(path).exists())
        ShadowLog.clear()

        AppearanceOverrideStore.setButtonMark(app, MimirButtonMark(id, path))
        applySnapshotWrites()

        val button = overlay.mountedButton()
        assertEquals("the theme carries the player's choice", CompanionButtonMark.Image(id, path), paintOf(button).mark)
        assertTrue(
            "the coin struck the rune's vector, not a bitmap",
            ReflectionHelpers.getField<Any?>(button, "mark") !is android.graphics.drawable.BitmapDrawable,
        )
        val lines = ShadowLog.getLogsForTag("MimirCompanionNative").map { it.msg }
        assertTrue("logged the fallback with the id: $lines", lines.contains("mark=rune reason=missing id=$id"))
        assertTrue("no path in any line", ShadowLog.getLogs().none { it.msg.contains("appearance/marks") })
    }

    /** Targets the ᛗ as `MButtonTopologyController.reconcile` does once the Bottom is eligible, and runs the mount. */
    private fun mountOnBottom(overlay: AndroidCompanionOverlayController): CompanionOverlayTarget {
        val target = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, bottomDisplayId)
        overlay.targetOverlay(target)
        idleMain()
        assertEquals("fixture: the ᛗ and its edge catcher mounted on the Bottom", target, overlay.activeTarget.value)
        return target
    }

    private fun AndroidCompanionOverlayController.mountedRoot(): View =
        checkNotNull(ReflectionHelpers.getField<View?>(this, "overlayView")) { "fixture: a view is mounted" }

    private fun AndroidCompanionOverlayController.mountedButton(): View {
        val coin = (mountedRoot() as ViewGroup).getChildAt(0)
        assertEquals("fixture: the mounted view holds the ᛗ coin", "CompanionMenuCoinView", coin.javaClass.simpleName)
        return coin
    }

    private fun AndroidCompanionOverlayController.mountedButtonPaint(): CompanionOverlayTheme = paintOf(mountedButton())

    /** The material the controller's next button, menu card and coins are built from: `buildButton` and `buildMenu` read it. */
    private fun AndroidCompanionOverlayController.buildMaterial(): CompanionOverlayTheme =
        ReflectionHelpers.getField(this, "overlayTheme")

    /** The material a coin's or the menu card's onDraw paints from. */
    private fun paintOf(view: View): CompanionOverlayTheme = ReflectionHelpers.getField(view, "theme")

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    /** A held press on the ᛗ, past the long-press timeout, then the button's fade out and the menu's arrival. */
    private fun longPress(button: View) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            assertTrue("fixture: the ᛗ took the press", button.dispatchTouchEvent(down))
        } finally {
            down.recycle()
        }
        Shadows.shadowOf(Looper.getMainLooper())
            .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1_500L))
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * What Compose runs after a write to global snapshot state once any composition exists in the process, as the Bottom's
     * floor and Presentation are: the apply notifications, then what they posted to the main looper.
     */
    private fun applySnapshotWrites() {
        Snapshot.sendApplyNotifications()
        idleMain()
    }

    /** The palette the Bottom's covers draw with: `MimirTheme` resolves `forId(themeId).withAppearanceOverrides()`. */
    private fun coversPalette(id: MimirThemeId): MimirThemePalette = MimirThemePalette.forId(id).withAppearanceOverrides()

    /** The override must move the two tokens the menu card's rim and sheen line draw, or a paint that ignores it would pass. */
    private fun assertOverrideRetintsCardRimAndSheen(id: MimirThemeId, covers: MimirThemePalette) {
        val authored = MimirThemePalette.forId(id)
        assertNotEquals("fixture: the override moves $id's accentBright, the card's rim", authored.accentBright, covers.accentBright)
        assertNotEquals(
            "fixture: the override moves $id's hero hardware, the card's sheen line",
            authored.materials.hero.hardware,
            covers.materials.hero.hardware,
        )
    }

    /**
     * Every overlay role, field by field, against the token of the covers' palette it names. `mutedInkArgb` is the one role
     * that is not a token: it is lifted from textSecondary toward onCover until it reads on coverTop and coverBottom, four
     * tokens the overrides leave authored, so it must stay what the same theme maps to with no override loaded.
     */
    private fun assertWearsCovers(
        where: String,
        covers: MimirThemePalette,
        authoredMutedInk: Int,
        paint: CompanionOverlayTheme,
    ) {
        val hero = covers.materials.hero
        val roles = listOf(
            Triple("coverArgb = hero.coverBottom", hero.coverBottom.toArgb(), paint.coverArgb),
            Triple("coverEdgeArgb = hero.hardware", hero.hardware.toArgb(), paint.coverEdgeArgb),
            Triple("pageArgb = hero.coverTop", hero.coverTop.toArgb(), paint.pageArgb),
            Triple("pageEdgeArgb, the card's rim = accentBright", covers.accentBright.toArgb(), paint.pageEdgeArgb),
            Triple("inkArgb = hero.onCover", hero.onCover.toArgb(), paint.inkArgb),
            Triple("mutedInkArgb = the theme's with no override", authoredMutedInk, paint.mutedInkArgb),
            Triple("accentArgb = counterAccent", covers.materials.counterAccent.toArgb(), paint.accentArgb),
            Triple("toolingArgb, the card's sheen = hero.hardware", hero.hardware.toArgb(), paint.toolingArgb),
            Triple("metalLightArgb = iconGradLight", covers.iconGradLight.toArgb(), paint.metalLightArgb),
            Triple("metalHotArgb = iconGradHot", covers.iconGradHot.toArgb(), paint.metalHotArgb),
            Triple("metalMidArgb = iconGradMid", covers.iconGradMid.toArgb(), paint.metalMidArgb),
            Triple("metalDeepArgb = iconGradDeep", covers.iconGradDeep.toArgb(), paint.metalDeepArgb),
        )
        assertEquals(
            "$where must paint every role from the covers' palette",
            roles.map { (role, expected, _) -> "$role ${hex(expected)}" },
            roles.map { (role, _, actual) -> "$role ${hex(actual)}" },
        )
    }

    /** Opens the menu with a long press on the mounted ᛗ and compares its card and every coin with the covers' palette. */
    private fun assertMenuWearsCovers(
        overlay: AndroidCompanionOverlayController,
        covers: MimirThemePalette,
        authoredMutedInk: Int,
    ) {
        longPress(overlay.mountedButton())
        assertTrue("fixture: the long press opened the menu", overlay.quickMenuOpen.value)
        val menu = overlay.mountedRoot().descendants().toList()
        val cards = menu.filter { it.javaClass.simpleName == "CompanionMenuGlassView" }
        val coins = menu.filter { it.javaClass.simpleName == "CompanionMenuCoinView" }
        assertEquals("fixture: the menu has one card", 1, cards.size)
        assertTrue("fixture: the menu has its coins (found ${coins.size})", coins.size >= 3)
        assertWearsCovers("the menu card", covers, authoredMutedInk, paintOf(cards.single()))
        coins.forEachIndexed { index, coin -> assertWearsCovers("menu coin $index", covers, authoredMutedInk, paintOf(coin)) }
    }

    /** The overlay's push lines under `MimirTheme`, with `pushUs` masked because its value is a timing. */
    private fun overlayPushLines(): List<String> = ShadowLog.getLogsForTag("MimirTheme")
        .map { it.msg }
        .filter { it.startsWith("overlay theme pushed ") }
        .map { it.replace(Regex(" pushUs=\\d+$"), " pushUs=<n>") }

    private fun hex(argb: Int): String = "#" + Integer.toHexString(argb and 0xffffff).padStart(6, '0')

    /** Written to the file only, as a previous process left it; nothing loads the store here. */
    private fun storeTheme(id: MimirThemeId) {
        check(appearancePrefs().edit().putString("themePreference", id.storageKey).commit())
    }

    /** `AppearanceOverrideStore`'s key for the Default accent stop, written to the file only. */
    private fun storeAccentStop(stop: MimirAccentStop) {
        check(appearancePrefs().edit().putString("appearanceAccentStop", stop.name).commit())
    }

    /** `AppearanceOverrideStore`'s key for a named theme's temperature, written to the file only. */
    private fun storeAccentTemp(id: MimirThemeId, temp: MimirAccentTemp) {
        check(appearancePrefs().edit().putString("appearanceAccentTemp.${id.name}", temp.name).commit())
    }

    private fun appearancePrefs() = app.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)

    /** With nothing stored, each load returns its process-wide holder to the value a process holds before any read. */
    private fun resetAppearanceToUnloaded() {
        appearancePrefs().edit().clear().commit()
        ThemeStore.load(app)
        AppearanceOverrideStore.load(app)
        // A live picker preview is never in the file, and the follow-up tests write the ones the Accent picker writes.
        MimirAppearanceOverrides.accentPreview = null
        MimirAppearanceOverrides.accentTempPreview = null
        MimirAppearanceOverrides.ringPreview = null
        check(ThemeStore.themeId.value == MimirThemeId.DEFAULT)
        check(MimirAppearanceOverrides.accentStop == MimirAccentStop.CLASSIC_GOLD)
        check(MimirAppearanceOverrides.accentTemps.isEmpty() && MimirAppearanceOverrides.ringStops.isEmpty())
    }

    /** The controller is a process singleton; each test is a fresh process. */
    private fun resetOverlaySingleton() {
        ReflectionHelpers.setStaticField(AndroidCompanionOverlayController::class.java, "shared", null)
    }

    private companion object {
        /** `companionOverlayMenuTitle(SECONDARY_COMPANION)`, which is internal to core-companion-native. */
        const val companionOverlayMenuTitleText = "ᛗ · Bottom screen"
    }
}
