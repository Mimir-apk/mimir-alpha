package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.view.Display
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * O15 follow-up (coordinator review, 2026-09-12): a game picked on the Bottom card that the session ledger refuses is told
 * what held it, once, on the Bottom ([showCompanionPickedGameRefusal]).
 *
 * The launch path runs in Main's composition and used to make this toast from Main's Activity context, so it appeared on
 * Main while the player was looking at the Bottom. The toast's display is read from the context it was made with, which
 * [ContextRecordingShadowToast] keeps. Its call site is pinned in `SessionMutexRefusalWiringTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    application = Application::class,
    shadows = [ContextRecordingShadowToast::class],
)
class CompanionPickedGameRefusalTest {

    private lateinit var app: Application
    private var bottomDisplayId = Display.INVALID_DISPLAY

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        bottomDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        ShadowDisplayManager.removeDisplay(bottomDisplayId)
        ShadowLooper.idleMainLooper()
    }

    /** Thor signatures (b) and (c): another game's card PLAY on the Bottom while a game runs on Main. */
    @Test
    fun `a card PLAY refused while a game runs is told it is still open, once, on the Bottom it was tapped on`() {
        showCompanionPickedGameRefusal(app, bottomDisplayId, "snes/metroid", GameLaunchSessionHold.RUNNING)

        assertEquals("one refusal, one toast", 1, ShadowToast.shownToastCount())
        assertEquals("A game or app is still open. Close it first, then try again.", ShadowToast.getTextOfLatestToast())
        assertEquals("made on the Bottom display", bottomDisplayId, latestToastDisplayId())
    }

    @Test
    fun `a card PLAY refused while a launch is still starting keeps today's sentence, on the Bottom`() {
        showCompanionPickedGameRefusal(app, bottomDisplayId, "snes/metroid", GameLaunchSessionHold.STARTING)

        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(
            "Mimir is already launching something else. Wait for it to finish, then try again.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals("made on the Bottom display", bottomDisplayId, latestToastDisplayId())
    }

    @Test
    fun `the log line names what held the launch`() {
        showCompanionPickedGameRefusal(app, bottomDisplayId, "snes/metroid", GameLaunchSessionHold.RUNNING)
        showCompanionPickedGameRefusal(app, bottomDisplayId, "snes/kirby", GameLaunchSessionHold.STARTING)

        assertEquals(
            listOf(
                "launch refused game=snes/metroid reason=session_held_running",
                "launch refused game=snes/kirby reason=session_held_starting",
            ),
            ShadowLog.getLogsForTag("MimirOpening").map { it.msg }.filter { it.startsWith("launch refused game=") },
        )
    }

    @Test
    fun `control - with no Bottom display the refusal stays on Main`() {
        showCompanionPickedGameRefusal(app, null, "snes/metroid", GameLaunchSessionHold.RUNNING)

        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(Display.DEFAULT_DISPLAY, latestToastDisplayId())
    }

    private fun latestToastDisplayId(): Int {
        val toast = checkNotNull(ShadowToast.getLatestToast()) { "no toast was shown" }
        val madeWith = checkNotNull(Shadow.extract<ContextRecordingShadowToast>(toast).madeWith) {
            "the toast recorded no context"
        }
        return displayIdOf(madeWith)
    }

    /** `Context.getDisplayId()` is hidden API, so it is read from the framework jar Robolectric runs. */
    private fun displayIdOf(context: Context): Int =
        Context::class.java.getMethod("getDisplayId").invoke(context) as Int
}
