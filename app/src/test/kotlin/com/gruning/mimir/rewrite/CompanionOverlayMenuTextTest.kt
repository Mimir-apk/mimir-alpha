package com.gruning.mimir.rewrite

import android.app.Application
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.companionnative.AndroidCompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * What the Mimir button's menu says (localization step 2), read off the real controller's views: the header, each coin's caption (its
 * title, as written) and each coin's spoken name (title and subtitle). The mount is `CompanionOverlayMenuLabelFontTest`'s; this reads text,
 * not geometry. Fitting the longer Spanish words on the Thor and the Odin is pending device visual review.
 */
abstract class CompanionOverlayMenuTextFixture {

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
        scope.cancel()
        resetOverlaySingleton()
        CompanionState.set(CompanionPayload.idle())
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    /** The menu's words: the header text, the captions in coin order, and the coins' spoken names in the same order. */
    class MenuText(val header: String, val captions: List<String>, val spoken: List<String>)

    protected fun openMenu(role: CompanionOverlayRole, returnAvailable: Boolean = false): MenuText {
        val overlay = startCompanionOverlay(app, scope)
        ReflectionHelpers.setField(overlay, "secondaryReturnAvailable", returnAvailable)
        val display = if (role == CompanionOverlayRole.PRIMARY_SINGLE) Display.DEFAULT_DISPLAY else bottomDisplayId
        overlay.targetOverlay(CompanionOverlayTarget(role, display))
        idle()
        ReflectionHelpers.callInstanceMethod<Unit>(overlay, "showMenu")
        advance(1_000)
        assertTrue("fixture: the menu is open", overlay.quickMenuOpen.value)
        val root = checkNotNull(ReflectionHelpers.getField<View?>(overlay, "overlayView"))
        val views = root.descendants().toList()
        val texts = views.filterIsInstance<TextView>()
        // The header is the one text that opens with the brand rune. A coin is the first view above a rune coin that carries a spoken name
        // (the menu's own `menuCoin` container); its caption is the one text inside it.
        val header = texts.single { it.text.startsWith("ᛗ") }.text.toString()
        val coins = views.filter { it.javaClass.simpleName == "CompanionMenuCoinView" }.map { rune ->
            generateSequence(rune) { view -> view.parent as? View }.first { view -> view.contentDescription != null }
        }
        val captions = coins.map { coin ->
            coin.descendants().filterIsInstance<TextView>().single().text.toString()
        }
        return MenuText(header, captions, coins.map { it.contentDescription.toString() })
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionOverlayMenuEnglishTextTest : CompanionOverlayMenuTextFixture() {

    @Test
    fun `the single-display menu reads as it always did`() {
        val menu = openMenu(CompanionOverlayRole.PRIMARY_SINGLE)
        assertEquals("ᛗ · Mimir", menu.header)
        assertEquals(listOf("Tabs", "Quick launch", "Utility", "Tweaker"), menu.captions)
        assertEquals(
            listOf(
                "Tabs. Android’s recent apps",
                "Quick launch. Open from anywhere",
                "Utility. Brightness & device controls",
                "Tweaker. Performance & telemetry",
            ),
            menu.spoken,
        )
        // One screen: the menu never talks about a second one.
        val visible = (menu.captions + menu.spoken).joinToString(" ").lowercase()
        listOf("companion", "second screen", "second-screen", "secondary", "bottom screen", "dual screen", "dual-screen")
            .forEach { banned -> assertFalse("Unexpected '$banned' in $visible", banned in visible) }
    }

    @Test
    fun `the Bottom menu names its display where more than one exists`() {
        val menu = openMenu(CompanionOverlayRole.SECONDARY_COMPANION, returnAvailable = true)
        assertEquals("ᛗ · Bottom screen", menu.header)
        assertEquals(listOf("Tabs", "Return to shelf", "Quick launch", "Utility", "Swap", "Trackpad"), menu.captions)
        assertEquals("Utility. Both screens’ brightness & device controls", menu.spoken[3])
        assertEquals("Quick launch. Opens on the bottom screen", menu.spoken[2])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "b+es+419")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionOverlayMenuSpanishTextTest : CompanionOverlayMenuTextFixture() {

    @Test
    fun `the single-display menu reads in Latin American Spanish, Tweaker left as its name`() {
        val menu = openMenu(CompanionOverlayRole.PRIMARY_SINGLE)
        assertEquals("ᛗ · Mimir", menu.header)
        assertEquals(listOf("Pestañas", "Acceso rápido", "Utilidades", "Tweaker"), menu.captions)
        assertEquals(
            listOf(
                "Pestañas. Apps recientes de Android",
                "Acceso rápido. Abrir desde cualquier lugar",
                "Utilidades. Brillo y controles del dispositivo",
                "Tweaker. Rendimiento y telemetría",
            ),
            menu.spoken,
        )
    }

    @Test
    fun `the Bottom menu reads in Spanish, with its display named`() {
        val menu = openMenu(CompanionOverlayRole.SECONDARY_COMPANION, returnAvailable = true)
        assertEquals("ᛗ · Pantalla inferior", menu.header)
        assertEquals(
            listOf("Pestañas", "Volver al estante", "Acceso rápido", "Utilidades", "Cambiar", "Panel táctil"),
            menu.captions,
        )
        assertEquals("Utilidades. Brillo y controles del dispositivo de ambas pantallas", menu.spoken[3])
        assertEquals("Acceso rápido. Se abre en la pantalla inferior", menu.spoken[2])
        assertEquals("Panel táctil. Mouse para la pantalla principal", menu.spoken[5])
    }
}
